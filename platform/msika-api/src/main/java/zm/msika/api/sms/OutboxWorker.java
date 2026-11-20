package zm.msika.api.sms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.sms.SmsGatewayException;
import zm.msika.core.sms.SmsGatewayPort;

/**
 * Sends what is waiting in the outbox (card M-18). Each message is locked while it is sent, so two servers never
 * send the same one at the same moment. A message the gateway refuses is tried again after a growing wait (10 s,
 * 30 s, 2 min, 10 min, 30 min); after the last try it is marked FAILED for a person to look at.
 *
 * <p>When the gateway is a relay that collects messages itself (card M-21), this worker leaves the outbox alone.
 */
@Component
public class OutboxWorker {

    /** The waits between tries; the number of tries is one more than this. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30));

    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);

    private final TenantContext tenant;
    private final SmsStore store;
    private final SmsGatewayPort gateway;
    private final Clock clock;
    private final int batch;

    OutboxWorker(TenantContext tenant, SmsStore store, SmsGatewayPort gateway, Clock clock,
            @Value("${msika.sms.outbox.batch:50}") int batch) {
        this.tenant = tenant;
        this.store = store;
        this.gateway = gateway;
        this.clock = clock;
        this.batch = batch;
    }

    /** Sends every message that is due, for every tenant. Returns how many were sent. */
    public int sendDue() {
        if (gateway.collectedByRelay()) {
            return 0;
        }
        int[] sent = {0};
        tenant.forEachSmsTenant(council -> {
            Instant now = clock.instant();
            for (SmsStore.Outbound message : store.claimDue(now, batch)) {
                if (sendOne(message, now)) {
                    sent[0]++;
                }
            }
        });
        return sent[0];
    }

    private boolean sendOne(SmsStore.Outbound message, Instant now) {
        try {
            String reference = gateway.send(message.id().toString(), message.toMsisdn(), message.body());
            store.markSent(message.id(), reference, now);
            return true;
        } catch (SmsGatewayException | IllegalArgumentException e) {
            int tries = message.attempts() + 1;
            String to = new PhoneNumber(message.toMsisdn()).toString();
            if (tries > BACKOFF.size()) {
                LOG.error("SMS {} to {} FAILED after {} tries: {}", message.id(), to, tries, e.getMessage());
                store.markFailed(message.id(), e.getMessage());
            } else {
                LOG.warn("SMS {} to {} not sent (try {}): {}", message.id(), to, tries, e.getMessage());
                store.markRetry(message.id(), e.getMessage(), now.plus(BACKOFF.get(tries - 1)));
            }
            return false;
        }
    }
}
