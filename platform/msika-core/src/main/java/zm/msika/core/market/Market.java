package zm.msika.core.market;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import zm.msika.core.common.InvalidInputException;

/**
 * A market run by a council.
 *
 * @param id the market's id
 * @param councilId the council that runs it
 * @param code a short code of 2 to 5 capital letters, the first part of every stall code there
 *     (the code {@code SOW} gives stalls like {@code SOW-B-114}); unique within the council
 * @param name the market's name
 * @param location where it is, free text, may be empty
 * @param dayCloseTime when the trading day is closed and sealed, in the council's time zone
 * @param createdAt when it was created
 */
public record Market(
        UUID id,
        UUID councilId,
        String code,
        String name,
        String location,
        LocalTime dayCloseTime,
        Instant createdAt) {

    private static final Pattern CODE = Pattern.compile("[A-Z]{2,5}");

    /** The default time a trading day is closed and sealed. */
    public static final LocalTime DEFAULT_DAY_CLOSE_TIME = LocalTime.of(20, 0);

    public Market {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(dayCloseTime, "dayCloseTime must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        code = code.trim().toUpperCase();
        name = name.trim();
        location = location == null ? "" : location.trim();
        if (!CODE.matcher(code).matches()) {
            throw new InvalidInputException("market code must be 2 to 5 capital letters, like SOW");
        }
        if (name.length() < 2 || name.length() > 100) {
            throw new InvalidInputException("market name must be 2 to 100 characters");
        }
        if (location.length() > 200) {
            throw new InvalidInputException("market location must be at most 200 characters");
        }
    }
}
