package zm.msika.core.council;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.InvalidInputException;

/**
 * A local authority. The tenant: each council sees only its own data (ARCHITECTURE.md rule 6).
 *
 * @param id the council's id
 * @param name the council's name, 2 to 100 characters
 * @param timezone the zone its business date is worked out in; Africa/Lusaka for every Zambian council
 * @param createdAt when it was created
 */
public record Council(UUID id, String name, ZoneId timezone, Instant createdAt) {

    public Council {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        name = name.trim();
        if (name.length() < 2 || name.length() > 100) {
            throw new InvalidInputException("council name must be 2 to 100 characters");
        }
    }
}
