package zm.msika.core.payments;

import java.util.Optional;
import java.util.UUID;

/**
 * The reference Msika puts on every payment request: {@code msika:<councilId>:<requestId>}. The payments
 * system echoes it back in its callback, and that is how Msika finds the council and the request an outcome
 * belongs to, without depending on the order in which things happen.
 *
 * <p>The callback is signed, so the reference can be trusted only when the signature has been checked.
 */
public final class PaymentReference {

    private static final String PREFIX = "msika:";

    /** The id parts of a reference. */
    public record Parts(UUID councilId, UUID requestId) {}

    private PaymentReference() {}

    public static String of(UUID councilId, UUID requestId) {
        return PREFIX + councilId + ":" + requestId;
    }

    /** Reads a reference, or returns empty if it is not one of ours (for example another system's payment). */
    public static Optional<Parts> parse(String reference) {
        if (reference == null || !reference.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = reference.substring(PREFIX.length()).split(":");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parts(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
