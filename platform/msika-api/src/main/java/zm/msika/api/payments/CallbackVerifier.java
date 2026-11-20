package zm.msika.api.payments;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decides whether a callback really came from the payments system: the signature must match the body and the
 * timestamp, and the timestamp must be recent. Rejecting old timestamps stops someone replaying a captured
 * callback later. Rejecting future ones stops a captured callback being pre-dated.
 *
 * <p>The signature is HMAC-SHA256 over {@code timestamp + "." + body}, sent as {@code sha256=<hex>}. With no
 * secret configured every callback is rejected: failing closed is the safe default.
 */
public final class CallbackVerifier {

    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";

    private static final String PREFIX = "sha256=";

    private final byte[] secret;
    private final Clock clock;
    private final Duration tolerance;

    public CallbackVerifier(String secret, Clock clock, Duration tolerance) {
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.tolerance = tolerance;
    }

    /** True only if the secret is set, the timestamp is within the window, and the signature matches. */
    public boolean isAuthentic(String timestampHeader, String signatureHeader, byte[] body) {
        if (secret == null || timestampHeader == null || signatureHeader == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long age = clock.instant().getEpochSecond() - timestamp;
        if (Math.abs(age) > tolerance.toSeconds()) {
            return false;
        }
        byte[] expected = sign(timestamp, body).getBytes(StandardCharsets.UTF_8);
        byte[] given = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison: how long it takes must not reveal how much of the signature was right.
        return MessageDigest.isEqual(expected, given);
    }

    /** The signature for a body, for tests and for anything that needs to call the endpoint as the payments system. */
    String sign(long timestamp, byte[] body) {
        if (secret == null) {
            throw new IllegalStateException("no callback secret is configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return PREFIX + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
