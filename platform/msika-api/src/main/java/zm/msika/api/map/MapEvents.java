package zm.msika.api.map;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zm.msika.api.deposit.DepositEventsHub;
import zm.msika.core.deposit.Deposit;

/**
 * The live closing view's feed (card M-51, MARKET-MAP section 8.4): each confirmed deposit is pushed, as a
 * server-sent event, to everyone watching that market's map. The event is sent only after the confirmation has
 * committed, so the map never shows a deposit that was rolled back.
 *
 * <p>Events are a nudge, not the record: a browser that missed some (it was reconnecting) reloads the map status,
 * which is always worked out from the database.
 */
@Component
public class MapEvents implements DepositEventsHub.Part {

    /** What the map is told. */
    public record DepositConfirmed(long id, UUID marketId, UUID stallId, String stallCode, UUID agentId, long amountNgwee,
            String reference, Instant at) {}

    private record Watcher(UUID councilId, SseEmitter emitter) {}

    private static final long TIMEOUT_MS = 30 * 60_000L;

    private final Map<UUID, List<Watcher>> byMarket = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final JdbcClient jdbc;
    private final Clock clock;

    MapEvents(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A new watcher of a market. The caller has already checked the user may see the council. */
    public SseEmitter watch(UUID councilId, UUID marketId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Watcher w = new Watcher(councilId, emitter);
        List<Watcher> list = byMarket.computeIfAbsent(marketId, k -> new CopyOnWriteArrayList<>());
        list.add(w);
        Runnable remove = () -> list.remove(w);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            // Tells the browser it is connected, and how long to wait before reconnecting if the line drops.
            emitter.send(SseEmitter.event().name("hello").reconnectTime(2_000).data("{}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Called in the confirming transaction; the event goes out only if and when it commits. */
    @Override
    public void confirmed(Deposit d) {
        record Where(UUID marketId, String code) {}
        Where where = jdbc.sql("select market_id, code from stall where council_id = :c and id = :s")
                .param("c", d.councilId()).param("s", d.stallId())
                .query((rs, n) -> new Where(rs.getObject("market_id", UUID.class), rs.getString("code"))).single();
        DepositConfirmed event = new DepositConfirmed(0, where.marketId(), d.stallId(), where.code(), d.agentId(),
                d.amount().minorUnits(), d.reference(), clock.instant());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(d.councilId(), event);
                }
            });
        } else {
            publish(d.councilId(), event);
        }
    }

    private void publish(UUID councilId, DepositConfirmed event) {
        List<Watcher> list = byMarket.getOrDefault(event.marketId(), List.of());
        DepositConfirmed numbered = new DepositConfirmed(ids.incrementAndGet(), event.marketId(), event.stallId(),
                event.stallCode(), event.agentId(), event.amountNgwee(), event.reference(), event.at());
        for (Watcher w : list) {
            if (!w.councilId().equals(councilId)) {
                continue; // a market id from another council: never send across councils
            }
            try {
                // One send at a time per emitter: Spring's emitter is not safe for concurrent sends.
                synchronized (w.emitter()) {
                    w.emitter().send(SseEmitter.event().id(Long.toString(numbered.id())).name("deposit").data(numbered));
                }
            } catch (IOException | IllegalStateException e) {
                list.remove(w);
            }
        }
    }

    /** A comment line every 20 s keeps proxies from closing a quiet connection and finds dead ones. */
    @Scheduled(fixedDelay = 20_000)
    void keepAlive() {
        byMarket.values().forEach(list -> list.forEach(w -> {
            try {
                synchronized (w.emitter()) {
                    w.emitter().send(SseEmitter.event().comment("keep-alive"));
                }
            } catch (IOException | IllegalStateException e) {
                list.remove(w);
            }
        }));
    }

    /** How many are watching a market (for tests and health). */
    public int watchers(UUID marketId) {
        return byMarket.getOrDefault(marketId, List.of()).size();
    }
}
