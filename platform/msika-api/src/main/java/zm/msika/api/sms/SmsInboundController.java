package zm.msika.api.sms;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.sms.SmsRouting;

/**
 * Where the SMS gateway delivers every text (docs/SMS-PROTOCOL.md, "Inbound webhook"). It checks the signature,
 * stores the message, answers at once, and only then processes it, so a slow use case can never make the gateway
 * time out and send the message again.
 *
 * <p>The sender's number decides which council the message belongs to, through the narrow lookup in migration
 * V10. A number nobody has registered gives a message that belongs to no council.
 */
@RestController
class SmsInboundController {

    static final String SIGNATURE_HEADER = "X-Msika-Signature";
    static final String TIMESTAMP_HEADER = "X-Msika-Timestamp";

    record Stored(boolean stored, Boolean duplicate) {}

    record Refused(String error, String message) {}

    private final CallbackVerifier verifier;
    private final TenantContext tenant;
    private final SmsStore store;
    private final SmsProcessor processor;
    private final Executor executor;
    private final Clock clock;

    SmsInboundController(@Qualifier("smsGatewayVerifier") CallbackVerifier verifier, TenantContext tenant,
            SmsStore store, SmsProcessor processor, @Qualifier("smsProcessingExecutor") Executor executor,
            Clock clock) {
        this.verifier = verifier;
        this.tenant = tenant;
        this.store = store;
        this.processor = processor;
        this.executor = executor;
        this.clock = clock;
    }

    @PostMapping("/api/v1/sms/inbound")
    ResponseEntity<?> inbound(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        if (!verifier.isAuthentic(timestamp, signature, body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new Refused("bad_signature", "the signature or timestamp is missing, wrong or too old"));
        }
        String gatewayId;
        PhoneNumber from;
        String text;
        Instant receivedAt;
        try {
            JsonNode node = JSON.readTree(body);
            gatewayId = text(node, "gatewayMessageId", "").strip();
            text = text(node, "body", null);
            from = PhoneNumber.parse(text(node, "from", ""));
            receivedAt = OffsetDateTime.parse(text(node, "receivedAt", "")).toInstant();
        } catch (RuntimeException e) {
            return bad("the message must have gatewayMessageId, from (a Zambian mobile number), body and receivedAt: "
                    + e.getMessage());
        }
        if (gatewayId.isEmpty() || gatewayId.length() > 200) {
            return bad("gatewayMessageId must be 1 to 200 characters");
        }
        if (text == null || text.length() > 1600) {
            return bad("body must be present and at most 1600 characters");
        }

        // Which council the message belongs to: the sender's, when exactly one council knows the number.
        List<SmsRouting.Sender> senders = store.findSenders(from.e164());
        UUID council = senders.size() == 1 ? senders.get(0).councilId() : null;
        UUID id = UUID.randomUUID();
        boolean stored = tenant.inSmsTenant(council, () -> store.insertInbound(new SmsStore.Inbound(id, council,
                gatewayId, from.e164(), text, receivedAt, clock.instant())));
        if (!stored) {
            return ResponseEntity.ok(new Stored(false, true));
        }
        executor.execute(() -> processor.process(council, id));
        return ResponseEntity.ok(new Stored(true, null));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String text(JsonNode node, String field, String otherwise) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : otherwise;
    }

    private static ResponseEntity<Refused> bad(String message) {
        return ResponseEntity.badRequest().body(new Refused("invalid_input", message));
    }
}
