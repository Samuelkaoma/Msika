package zm.msika.zamsim.callback;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the queued callbacks to Msika, signed, and retries the ones that fail.
 *
 * <p>Each delivery is first <em>claimed</em>: its attempt count goes up and its next attempt moves a
 * minute into the future, so two workers never send the same delivery and a crash mid-send just means it
 * is tried again later. That makes delivery <strong>at least once</strong>, which is exactly how real
 * payment callbacks behave and why Msika must treat them as idempotent.
 *
 * <p>Any answer other than 2xx, a timeout or a refused connection counts as a failure. A failed delivery
 * waits longer each time (see {@link CallbackConfig}) and, after the last attempt, is marked DEAD.
 */
@Component
public class CallbackWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackWorker.class);

    /** How long a claimed delivery is protected from other workers. */
    private static final Duration LEASE = Duration.ofSeconds(60);

    private static final int BATCH = 50;

    private record Claimed(long id, UUID callbackId, String payload, int attempts) {}

    private final JdbcClient jdbc;
    private final CallbackConfig config;
    private final Clock clock;
    private final HttpClient http;

    CallbackWorker(JdbcClient jdbc, CallbackConfig config, Clock clock) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMs())).build();
    }

    /** Runs by itself every few hundred milliseconds. Tests switch it off and call {@link #runOnce()}. */
    @Scheduled(fixedDelayString = "${zamsim.callback.worker.poll-ms:250}")
    void poll() {
        if (config.enabled() && config.workerEnabled()) {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.error("callback worker failed", e);
            }
        }
    }

    /**
     * Sends every delivery that is due now, once each.
     *
     * @return how many attempts were made
     */
    public int runOnce() {
        if (!config.enabled()) {
            return 0;
        }
        List<Claimed> claimed = claim();
        for (Claimed delivery : claimed) {
            attempt(delivery);
        }
        return claimed.size();
    }

    private List<Claimed> claim() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return jdbc.sql("update callback_delivery set attempts = attempts + 1, next_attempt_at = :lease "
                        + "where id in (select id from callback_delivery where status = 'PENDING' "
                        + "and next_attempt_at <= :now order by next_attempt_at, id limit :batch for update skip locked) "
                        + "returning id, callback_id, payload, attempts")
                .param("lease", now.plus(LEASE)).param("now", now).param("batch", BATCH)
                .query((rs, row) -> new Claimed(
                        rs.getLong("id"), rs.getObject("callback_id", UUID.class), rs.getString("payload"),
                        rs.getInt("attempts")))
                .list().stream().sorted(java.util.Comparator.comparingLong(Claimed::id)).toList();
    }

    private void attempt(Claimed delivery) {
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(CallbackSigner.SIGNATURE_HEADER, config.signer().sign(timestamp, delivery.payload()))
                .header(CallbackSigner.CALLBACK_ID_HEADER, delivery.callbackId().toString())
                .header(CallbackSigner.ATTEMPT_HEADER, Integer.toString(delivery.attempts()))
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payload(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                delivered(delivery, response.statusCode());
            } else {
                failed(delivery, response.statusCode(), "receiver answered " + response.statusCode());
            }
        } catch (IOException e) {
            failed(delivery, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(delivery, null, "interrupted");
        }
    }

    private void delivered(Claimed delivery, int status) {
        jdbc.sql("update callback_delivery set status = 'DELIVERED', last_http_status = :status, last_error = null, "
                        + "delivered_at = :now where id = :id")
                .param("status", status).param("id", delivery.id())
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
    }

    private void failed(Claimed delivery, Integer status, String error) {
        if (delivery.attempts() >= config.maxAttempts()) {
            LOG.warn("callback {} gave up after {} attempts: {}", delivery.callbackId(), delivery.attempts(), error);
            jdbc.sql("update callback_delivery set status = 'DEAD', last_http_status = :status, last_error = :error "
                            + "where id = :id")
                    .param("status", status).param("error", error).param("id", delivery.id()).update();
            return;
        }
        OffsetDateTime next = OffsetDateTime.ofInstant(
                clock.instant().plusMillis(config.backoffAfterAttempt(delivery.attempts())), ZoneOffset.UTC);
        jdbc.sql("update callback_delivery set last_http_status = :status, last_error = :error, "
                        + "next_attempt_at = :next where id = :id")
                .param("status", status).param("error", error).param("next", next).param("id", delivery.id()).update();
    }
}
