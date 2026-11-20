package zm.msika.api.sms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.sms.SmsCommandParser;
import zm.msika.core.sms.SmsRouting;

/**
 * Processes one stored text: rate limit, parse, route, run the command, queue the replies, all in one transaction
 * as the message's tenant (card M-18). Safe to call twice for the same message: the row is locked and only a
 * message still {@code RECEIVED} is processed.
 */
@Service
public class SmsProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SmsProcessor.class);

    private final TenantContext tenant;
    private final SmsStore store;
    private final SmsCommandParser parser;
    private final SmsCommandHandler handler;
    private final Clock clock;
    private final int rateLimit;
    private final Duration rateWindow;
    private final long maxNgwee;

    SmsProcessor(TenantContext tenant, SmsStore store, SmsCommandHandler handler, Clock clock,
            @Value("${msika.sms.rate-limit.messages:20}") int rateLimit,
            @Value("${msika.sms.rate-limit.window:PT10M}") Duration rateWindow,
            @Value("${msika.sms.max-amount-ngwee:" + SmsCommandParser.DEFAULT_MAX_NGWEE + "}") long maxNgwee) {
        this.tenant = tenant;
        this.store = store;
        this.handler = handler;
        this.clock = clock;
        this.rateLimit = rateLimit;
        this.rateWindow = rateWindow;
        this.maxNgwee = maxNgwee;
        this.parser = new SmsCommandParser(maxNgwee);
    }

    /**
     * Processes the message if it is still waiting. If processing throws, everything it did is rolled back and the
     * message is marked FAILED (with no reply) so that it is not tried forever; the error is logged for a person.
     */
    public void process(UUID councilIdOrNull, UUID inboundId) {
        try {
            tenant.inSmsTenant(councilIdOrNull, () -> {
                store.claimInbound(inboundId).ifPresent(this::processClaimed);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("processing SMS {} failed; marked FAILED", inboundId, e);
            tenant.inSmsTenant(councilIdOrNull, () -> {
                store.claimInbound(inboundId).ifPresent(m ->
                        store.finishInbound(m.id(), "FAILED", "ERROR", clock.instant()));
                return null;
            });
        }
    }

    /** Processes waiting messages stored more than a little while ago, for every tenant. Run by a schedule. */
    public void sweep(Duration olderThan) {
        Instant before = clock.instant().minus(olderThan);
        java.util.Map<UUID, UUID> waiting = new java.util.LinkedHashMap<>();
        tenant.forEachSmsTenant(council -> store.waitingSince(before, 200).forEach(id -> waiting.put(id, council)));
        waiting.forEach((id, council) -> process(council, id));
    }

    private void processClaimed(SmsStore.Inbound message) {
        Instant now = clock.instant();
        String masked = new PhoneNumber(message.fromMsisdn()).toString();
        int recent = store.countFrom(message.fromMsisdn(), message.storedAt().minus(rateWindow), message.storedAt());
        if (recent > rateLimit) {
            LOG.warn("rate limit: {} messages from {} in {}; message stored, not answered", recent, masked, rateWindow);
            store.finishInbound(message.id(), "RATE_LIMITED", "RATE_LIMITED", now);
            return;
        }

        List<SmsRouting.Sender> senders = store.findSenders(message.fromMsisdn());
        SmsRouting.Decision decision = SmsRouting.decide(senders, parser.parse(message.body()), maxNgwee);
        switch (decision) {
            case SmsRouting.Decision.Answer answer -> {
                String body = answer.template().render(answer.values());
                store.enqueue(message.councilId(), message.fromMsisdn(), body, answer.template().name(), message.id(), now);
                store.finishInbound(message.id(), "PROCESSED", answer.outcome(), now);
            }
            case SmsRouting.Decision.Handle handle -> {
                SmsCommandHandler.Result result = handler.handle(handle.sender(), message.fromMsisdn(), handle.command(),
                        message.receivedAt());
                for (SmsCommandHandler.Reply reply : result.replies()) {
                    store.enqueue(message.councilId(), reply.toMsisdn(), reply.body(), reply.template().name(),
                            reply.toMsisdn().equals(message.fromMsisdn()) ? message.id() : null, now);
                }
                store.finishInbound(message.id(), "PROCESSED", result.outcome(), now);
            }
        }
    }
}
