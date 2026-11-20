package zm.msika.core.plate;

import java.util.Objects;

/**
 * What is printed on one stall's plate, and what its QR code says.
 *
 * <p>The QR code holds a small URI, {@code msika://stall/SOW-B-114?t=<token>}: the stall code so a
 * person or app knows which stall, and the secret token so only someone holding the printed plate can
 * claim to be at that stall. The agent app (M-40) reads it back with {@link #parseQr}.
 *
 * @param stallCode the stall's code, like {@code SOW-B-114}
 * @param marketName the market's name, printed on the plate
 * @param qrToken the stall's secret token, only ever shown as the QR code
 */
public record StallPlate(String stallCode, String marketName, String qrToken) {

    private static final String PREFIX = "msika://stall/";
    private static final String TOKEN_KEY = "?t=";

    /** The two parts a QR code carries. */
    public record Scanned(String stallCode, String qrToken) {}

    public StallPlate {
        Objects.requireNonNull(stallCode, "stallCode must not be null");
        Objects.requireNonNull(marketName, "marketName must not be null");
        Objects.requireNonNull(qrToken, "qrToken must not be null");
    }

    /** The exact text encoded in the QR code. */
    public String qrText() {
        return PREFIX + stallCode + TOKEN_KEY + qrToken;
    }

    /** Reads a scanned QR text, or returns null if it is not one of ours. */
    public static Scanned parseQr(String text) {
        if (text == null || !text.startsWith(PREFIX)) {
            return null;
        }
        String rest = text.substring(PREFIX.length());
        int split = rest.indexOf(TOKEN_KEY);
        if (split <= 0 || split + TOKEN_KEY.length() >= rest.length()) {
            return null;
        }
        return new Scanned(rest.substring(0, split), rest.substring(split + TOKEN_KEY.length()));
    }

    /** Never prints the secret token. */
    @Override
    public String toString() {
        return "StallPlate[" + stallCode + ", " + marketName + "]";
    }
}
