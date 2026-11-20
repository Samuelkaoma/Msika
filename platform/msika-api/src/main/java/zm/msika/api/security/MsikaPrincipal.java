package zm.msika.api.security;

import java.util.UUID;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;

/**
 * The signed-in user as the rest of the application sees them. Built from the user's database row on
 * every request, never from the token, so the role and the active flag are always current.
 *
 * @param userId the user's id
 * @param councilId their council, or {@code null} for an operator
 * @param role what they may do
 * @param email their sign-in name
 */
public record MsikaPrincipal(UUID userId, UUID councilId, Role role, String email)
        implements java.io.Serializable {

    static MsikaPrincipal of(CouncilUser user) {
        return new MsikaPrincipal(user.id(), user.councilId(), user.role(), user.email());
    }
}
