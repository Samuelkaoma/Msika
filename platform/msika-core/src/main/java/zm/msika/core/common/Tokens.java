package zm.msika.core.common;

import java.security.SecureRandom;
import java.util.Base64;

/** Random, unguessable tokens (for example the secret printed in a stall's QR code). */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** A new token with 128 bits of randomness, as 22 URL-safe characters. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
