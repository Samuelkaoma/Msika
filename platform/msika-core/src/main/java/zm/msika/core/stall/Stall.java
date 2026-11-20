package zm.msika.core.stall;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import zm.msika.core.common.InvalidInputException;

/**
 * A trading stall.
 *
 * @param id the stall's id
 * @param councilId the council it belongs to
 * @param marketId the market it is in
 * @param code its code, like {@code SOW-B-114}: market code, section, number. Unique in the council.
 * @param stallClass its class (for example {@code STANDARD}), which decides the levy
 * @param status vacant, allocated or suspended
 * @param qrToken the random secret printed in the stall's QR code (never derived from the code)
 */
public record Stall(
        UUID id,
        UUID councilId,
        UUID marketId,
        String code,
        String stallClass,
        StallStatus status,
        String qrToken) {

    private static final Pattern CLASS = Pattern.compile("[A-Z0-9_]{1,20}");

    public Stall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(stallClass, "stallClass must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(qrToken, "qrToken must not be null");
        if (!CLASS.matcher(stallClass).matches()) {
            throw new InvalidInputException("stall class must be 1 to 20 capitals, digits or underscores");
        }
        if (qrToken.length() < 16) {
            throw new InvalidInputException("qr token is too short to be secret");
        }
    }

    /** The same stall with a new status. */
    public Stall withStatus(StallStatus newStatus) {
        return new Stall(id, councilId, marketId, code, stallClass, newStatus, qrToken);
    }

    /**
     * Builds a stall code: the market code, the section and the number joined by dashes.
     *
     * @param marketCode the market's code, like {@code SOW}
     * @param section 1 to 4 capital letters or digits, like {@code B}
     * @param number 1 to 5 digits, like {@code 114}
     */
    public static String codeOf(String marketCode, String section, String number) {
        return marketCode + "-" + section + "-" + number;
    }
}
