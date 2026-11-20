package zm.msika.zamsim.callback;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.zamsim.fault.FaultService;
import zm.msika.zamsim.fault.FaultSettings;
import zm.msika.zamsim.wallet.Wallets.Transaction;

/**
 * Writes the outbox of callbacks. {@link #onCompleted} is called inside the same database transaction that
 * settles a payment, so a callback exists if and only if the payment really settled (the "outbox" pattern),
 * and a restart never loses one. {@link CallbackWorker} sends them.
 *
 * <p>The fault settings decide when each callback is first due: after {@code callbackDelayMs}, plus an
 * extra delay for a reordered one, and a duplicate is a second delivery of the same callback id.
 */
@Service
public class CallbackService {

    /** One delivery of a callback, as the console shows it. */
    public record Delivery(
            long id,
            UUID callbackId,
            UUID transactionId,
            String status,
            int attempts,
            Integer lastHttpStatus,
            String lastError,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant deliveredAt) {}

    private final JdbcClient jdbc;
    private final CallbackConfig config;
    private final FaultService faults;
    private final Clock clock;

    CallbackService(JdbcClient jdbc, CallbackConfig config, FaultService faults, Clock clock) {
        this.jdbc = jdbc;
        this.config = config;
        this.faults = faults;
        this.clock = clock;
    }

    /** Queues the callback for a payment that has just reached its final state. Does nothing if callbacks are off. */
    @Transactional
    public void onCompleted(Transaction transaction) {
        if (!config.enabled()) {
            return;
        }
        FaultSettings settings = faults.settings();
        UUID callbackId = UUID.randomUUID();
        String payload = payload(callbackId, transaction);

        long delay = settings.callbackDelayMs();
        if (faults.happens(settings.reorderRate())) {
            delay += settings.reorderExtraDelayMs();
        }
        Instant due = clock.instant().plusMillis(delay);
        insert(callbackId, transaction.id(), payload, due);
        if (faults.happens(settings.duplicateCallbackRate())) {
            insert(callbackId, transaction.id(), payload, due);
        }
    }

    private void insert(UUID callbackId, UUID transactionId, String payload, Instant due) {
        jdbc.sql("insert into callback_delivery (callback_id, transaction_id, payload, status, attempts, "
                        + "next_attempt_at, created_at) values (:cb, :tx, :payload, 'PENDING', 0, :due, :now)")
                .param("cb", callbackId).param("tx", transactionId).param("payload", payload)
                .param("due", OffsetDateTime.ofInstant(due, ZoneOffset.UTC))
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    /**
     * The exact text that is signed and sent. Built by hand so the bytes are the same every time and for
     * every retry of one callback.
     */
    static String payload(UUID callbackId, Transaction t) {
        return "{\"callbackId\":" + string(callbackId.toString())
                + ",\"event\":\"transaction.completed\""
                + ",\"transaction\":{"
                + "\"id\":" + string(t.id().toString())
                + ",\"kind\":" + string(t.kind().name())
                + ",\"status\":" + string(t.status().name())
                + ",\"fromWalletId\":" + string(t.fromWalletId().toString())
                + ",\"toWalletId\":" + string(t.toWalletId().toString())
                + ",\"amountNgwee\":" + t.amountNgwee()
                + ",\"reference\":" + (t.reference() == null ? "null" : string(t.reference()))
                + ",\"failureReason\":" + (t.failureReason() == null ? "null" : string(t.failureReason()))
                + ",\"createdAt\":" + string(t.createdAt().toString())
                + ",\"completedAt\":" + (t.completedAt() == null ? "null" : string(t.completedAt().toString()))
                + "}}";
    }

    /** A JSON string literal, with the characters JSON requires escaped. */
    static String string(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Deliveries for the console, newest first. {@code status} may be null for all. */
    @Transactional(readOnly = true)
    public List<Delivery> deliveries(String status, int limit) {
        return jdbc.sql("select id, callback_id, transaction_id, status, attempts, last_http_status, last_error, "
                        + "next_attempt_at, created_at, delivered_at from callback_delivery "
                        + "where (cast(:status as text) is null or status = :status) order by id desc limit :limit")
                .param("status", status).param("limit", limit)
                .query((rs, row) -> new Delivery(
                        rs.getLong("id"),
                        rs.getObject("callback_id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        (Integer) rs.getObject("last_http_status"),
                        rs.getString("last_error"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("delivered_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("delivered_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
