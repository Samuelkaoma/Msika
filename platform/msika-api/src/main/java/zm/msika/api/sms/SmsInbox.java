package zm.msika.api.sms;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.sms.SmsRouting;

/**
 * Takes in one received text: stores it once, as the council of whoever sent it (or as unassigned), then hands it
 * to the processor on another thread. Every way a text can arrive (the gateway webhook, the sim console's fake
 * phones, the relay) comes through here, so they all behave the same.
 */
@Service
public class SmsInbox {

    private final TenantContext tenant;
    private final SmsStore store;
    private final SmsProcessor processor;
    private final ExecutorService executor;
    private final Clock clock;

    SmsInbox(TenantContext tenant, SmsStore store, SmsProcessor processor,
            @Qualifier("smsProcessingExecutor") ExecutorService executor, Clock clock) {
        this.tenant = tenant;
        this.store = store;
        this.processor = processor;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * @return true if stored now, false if a text with this gateway id was already stored (and nothing was done)
     */
    public boolean receive(String gatewayMessageId, PhoneNumber from, String body, Instant receivedAt) {
        // Which council the text belongs to: the sender's, when exactly one council knows the number.
        List<SmsRouting.Sender> senders = store.findSenders(from.e164());
        UUID council = senders.size() == 1 ? senders.get(0).councilId() : null;
        UUID id = UUID.randomUUID();
        boolean stored = tenant.inSmsTenant(council, () -> store.insertInbound(new SmsStore.Inbound(id, council,
                gatewayMessageId, from.e164(), body, receivedAt, clock.instant())));
        if (stored) {
            executor.execute(() -> processor.process(council, id));
        }
        return stored;
    }
}
