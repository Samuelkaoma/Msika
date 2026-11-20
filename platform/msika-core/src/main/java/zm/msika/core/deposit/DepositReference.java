package zm.msika.core.deposit;

import java.util.Random;

/**
 * The four-character reference a trader quotes to dispute a deposit (SMS-PROTOCOL.md). It uses only
 * {@value #ALPHABET}: no 0/O and no 1/I/L, which look alike on a small screen and when read out.
 */
public final class DepositReference {

    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int LENGTH = 4;

    private DepositReference() {}

    public static String generate(Random random) {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /** True if the text is exactly four characters of the alphabet (upper case). */
    public static boolean isValid(String text) {
        if (text == null || text.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (ALPHABET.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
