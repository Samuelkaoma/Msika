package zm.msika.core.levy;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What a stall of one class in one market pays for each trading day, from a date on (ARCHITECTURE.md section 4.3).
 * A council changes its levy by adding a rule with a later start date, never by editing an old one, so the levy
 * charged on any past day can always be worked out again.
 *
 * @param amountNgwee the levy for one trading day, in whole ngwee
 * @param validFrom the first business date the rule applies to
 */
public record LevyRule(UUID id, UUID councilId, UUID marketId, String stallClass, long amountNgwee, LocalDate validFrom) {

    public LevyRule {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(stallClass, "stallClass must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (amountNgwee <= 0) {
            throw new IllegalArgumentException("a levy must be more than zero");
        }
    }
}
