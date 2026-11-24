package zm.msika.api.security;

import java.util.List;
import java.util.UUID;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.RoleGrant;

/**
 * The signed-in user as the rest of the application sees them. Built from the user's database row on
 * every request, never from the token, so the role and the active flag are always current.
 *
 * @param userId the user's id
 * @param councilId their council, or {@code null} for an operator
 * @param grants the roles they hold
 * @param email their sign-in name
 */
public record MsikaPrincipal(UUID userId, UUID councilId, List<RoleGrant> grants, String email)
        implements java.io.Serializable {

    static MsikaPrincipal of(CouncilUser user) {
        return new MsikaPrincipal(user.id(), user.councilId(), user.grants(), user.email());
    }

    /** True for Msika staff, who belong to no council. */
    public boolean isOperator() {
        return grants.stream().anyMatch(g -> g.role() == Role.MSIKA_OPERATOR);
    }
}
