package zm.msika.core.user;

import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.NotFoundException;

/**
 * Use case: deactivate or reactivate a council user. Deactivation takes effect on the user's very
 * next request, because every request re-reads the user.
 */
public final class SetUserActive {

    private final UserRepository users;

    public SetUserActive(UserRepository users) {
        this.users = Objects.requireNonNull(users);
    }

    /** @throws NotFoundException if the user is not in this council */
    public CouncilUser execute(UUID councilId, UUID userId, boolean active) {
        CouncilUser user = users.findInCouncil(councilId, userId)
                .orElseThrow(() -> new NotFoundException("user not found"));
        if (user.active() != active) {
            users.setActive(councilId, userId, active);
        }
        return user.withActive(active);
    }
}
