package zm.msika.core.stall;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A trader holding a stall for a period.
 *
 * @param id the allocation's id
 * @param councilId the council
 * @param stallId the stall held
 * @param traderId the trader holding it
 * @param validFrom when the trader got the stall
 * @param validTo when the trader gave it up, or {@code null} while they still hold it
 */
public record StallAllocation(
        UUID id, UUID councilId, UUID stallId, UUID traderId, Instant validFrom, Instant validTo) {

    public StallAllocation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(stallId, "stallId must not be null");
        Objects.requireNonNull(traderId, "traderId must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
    }

    /** True while the trader still holds the stall. */
    public boolean isActive() {
        return validTo == null;
    }
}
