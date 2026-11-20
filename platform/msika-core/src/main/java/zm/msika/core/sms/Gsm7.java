package zm.msika.core.sms;

/**
 * The GSM 03.38 default alphabet: the characters one plain SMS can carry, 160 to a message. A single character
 * outside it (an emoji, a curly quote) switches the whole message to UCS-2, which fits only 70 characters, so a
 * reply that looked like one SMS would arrive as three (AGENTS.md rule 10).
 *
 * <p>Nine characters are in the "extension table" and cost two septets each: {@code ^ { } \ [ ~ ] | €}.
 */
public final class Gsm7 {

    private static final String BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String EXTENSION = "^{}\\[~]|€";

    /** The most septets one SMS carries. */
    public static final int MAX_SEPTETS = 160;

    private Gsm7() {}

    /** True if every character can be sent in a plain GSM-7 SMS. */
    public static boolean isEncodable(String text) {
        return text.chars().allMatch(c -> BASIC.indexOf(c) >= 0 || EXTENSION.indexOf(c) >= 0);
    }

    /** The number of septets the text takes, counting extension characters twice. */
    public static int septets(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            n += EXTENSION.indexOf(text.charAt(i)) >= 0 ? 2 : 1;
        }
        return n;
    }

    /** True if the text is GSM-7 and fits one SMS. */
    public static boolean fitsOneSms(String text) {
        return isEncodable(text) && septets(text) <= MAX_SEPTETS;
    }

    /**
     * Makes free text (a person's name, a reason) safe for a reply: characters outside the basic alphabet become
     * {@code ?}, and extension characters become a space, so each character costs exactly one septet.
     */
    public static String sanitise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.append(' ');
            } else if (BASIC.indexOf(c) >= 0) {
                out.append(c);
            } else if (EXTENSION.indexOf(c) >= 0) {
                out.append(' ');
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }
}
