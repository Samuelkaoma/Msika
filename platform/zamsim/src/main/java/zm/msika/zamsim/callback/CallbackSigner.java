package zm.msika.zamsim.callback;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs callbacks so Msika can tell a real one from a forgery (ARCHITECTURE.md sections 6 and 9).
 *
 * <p>The signature is {@code HMAC-SHA256(secret, timestamp + "." + body)}, sent as
 * {@code X-Signature: sha256=<hex>} with the timestamp (whole seconds since 1970) in {@code X-Timestamp}.
 * The timestamp is part of what is signed, so an attacker cannot replay an old callback with a fresh
 * timestamp, and the receiver rejects a timestamp more than five minutes old.
 *
 * <p>This class is plain Java so Msika's own checker (M-14) can be written to match it exactly. Msika
 * must not import it: the two are separate programs that only agree on this written format.
 */
public final class CallbackSigner {

    /** The header carrying the signature. */
    public static final String SIGNATURE_HEADER = "X-Signature";

    /** The header carrying the timestamp in whole seconds. */
    public static final String TIMESTAMP_HEADER = "X-Timestamp";

    /** The header carrying the callback id, the same for every delivery of one callback. */
    public static final String CALLBACK_ID_HEADER = "X-Callback-Id";

    /** The header carrying which delivery attempt this is, starting at 1. */
    public static final String ATTEMPT_HEADER = "X-Attempt";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public CallbackSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("the callback secret must not be blank");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** The value of the {@code X-Signature} header for this body sent at this timestamp. */
    public String sign(long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] hmac = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(hmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
        }
    }

    /** True if the signature is the right one for this body and timestamp. Compares in constant time. */
    public boolean verify(long timestampSeconds, String body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(timestampSeconds, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
