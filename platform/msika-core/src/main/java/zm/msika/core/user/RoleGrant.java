package zm.msika.core.user;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import zm.msika.core.common.InvalidInputException;

/** A role a user holds, and for a market-scoped role the markets it is held for (at least one; none otherwise). */
public record RoleGrant(Role role, Set<UUID> marketIds) implements Serializable {

    public RoleGrant {
        Objects.requireNonNull(role, "role must not be null");
        marketIds = marketIds == null ? Set.of() : Set.copyOf(marketIds);
        if (role.marketScoped() == marketIds.isEmpty()) {
            throw new InvalidInputException(role.marketScoped() ? role + " needs at least one market"
                    : role + " is council-wide and takes no markets");
        }
    }

    public static RoleGrant councilWide(Role role) {
        return new RoleGrant(role, Set.of());
    }
}
