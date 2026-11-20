package zm.msika.api.payments;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zm.msika.core.payments.HandlePaymentEvent;
import zm.msika.core.payments.PaymentEvent;

/**
 * Where the payments system tells Msika that a payment reached its final state.
 *
 * <p>This path is open to the internet, so it trusts nothing until the signature is checked, and the check is on
 * the exact bytes received (a re-serialised body would not match). The answer tells the sender what to do next:
 *
 * <ul>
 *   <li>401: not signed properly, or too old. Do not retry; this is not from the payments system.
 *   <li>400: signed, but not a callback Msika understands. Retrying will not help.
 *   <li>200: handled, including a harmless duplicate and a payment that is not Msika's. Stop sending it.
 *   <li>5xx: something is wrong on Msika's side (the database, an amount that does not match). Send it again.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentCallbackController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentCallbackController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CallbackVerifier verifier;
    private final PaymentService payments;

    PaymentCallbackController(CallbackVerifier verifier, PaymentService payments) {
        this.verifier = verifier;
        this.payments = payments;
    }

    @PostMapping("/callback")
    ResponseEntity<Map<String, String>> callback(
            @RequestHeader(value = CallbackVerifier.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = CallbackVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        if (!verifier.isAuthentic(timestamp, signature, body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad_signature"));
        }
        PaymentEvent event;
        try {
            event = parse(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad_payload"));
        }
        try {
            HandlePaymentEvent.Outcome outcome = payments.handle(event);
            return ResponseEntity.ok(Map.of("outcome", outcome.name()));
        } catch (RuntimeException e) {
            // Not our caller's fault, so say so: the sender will try again later.
            LOG.error("payment callback {} could not be applied: {}", event.callbackId(), e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "not_applied"));
        }
    }

    static PaymentEvent parse(byte[] body) {
        JsonNode root = JSON.readTree(body);
        JsonNode t = root.path("transaction");
        if (!t.isObject()) {
            throw new IllegalArgumentException("no transaction");
        }
        String status = text(t, "status");
        PaymentEvent.Status mapped = switch (status) {
            case "SUCCEEDED" -> PaymentEvent.Status.SUCCEEDED;
            case "FAILED" -> PaymentEvent.Status.FAILED;
            default -> throw new IllegalArgumentException("not a final status: " + status);
        };
        // A missing or broken amount must be refused here (400, never retried), not read as 0 and then refused by
        // the ledger, which would answer 500 and make the sender retry the same bad callback forever.
        JsonNode amount = t.path("amountNgwee");
        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.asLong() <= 0) {
            throw new IllegalArgumentException("amountNgwee must be a whole number of ngwee above zero");
        }
        try {
            return new PaymentEvent(
                    optional(root, "callbackId"),
                    text(t, "id"),
                    mapped,
                    amount.asLong(),
                    optional(t, "reference"),
                    optional(t, "failureReason"),
                    Instant.parse(text(t, "completedAt")),
                    optional(t, "kind"),
                    optional(t, "fromWalletId"),
                    optional(t, "toWalletId"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("bad completedAt", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isString() || v.asString().isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return v.asString();
    }

    private static String optional(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : null;
    }
}
