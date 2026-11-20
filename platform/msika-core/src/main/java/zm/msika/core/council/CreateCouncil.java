package zm.msika.core.council;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.InvalidInputException;

/** Use case: an operator creates a council. */
public final class CreateCouncil {

    /** Every Zambian council works in this zone. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Lusaka");

    private final CouncilRepository councils;
    private final Clock clock;

    public CreateCouncil(CouncilRepository councils, Clock clock) {
        this.councils = Objects.requireNonNull(councils);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param name the council's name
     * @param timezone a zone id such as {@code Africa/Lusaka}, or null for the default
     */
    public Council execute(String name, String timezone) {
        ZoneId zone = DEFAULT_ZONE;
        if (timezone != null && !timezone.isBlank()) {
            try {
                zone = ZoneId.of(timezone.trim());
            } catch (DateTimeException e) {
                throw new InvalidInputException("unknown timezone: " + timezone);
            }
        }
        Council council = new Council(UUID.randomUUID(), name == null ? "" : name, zone, clock.instant());
        councils.add(council);
        return council;
    }
}
