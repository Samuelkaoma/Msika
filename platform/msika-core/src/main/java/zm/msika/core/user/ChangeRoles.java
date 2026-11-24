package zm.msika.core.user;

import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;

/** Use case: grant or revoke a user's role, keeping duties apart (COUNCIL-ADMIN.md section 3.3). */
public final class ChangeRoles {

    private final UserRepository users;

    public ChangeRoles(UserRepository users) {
        this.users = Objects.requireNonNull(users);
    }

    /**
     * @param by the id of the user making the change
     * @throws ConflictException if it is the user's own role, they hold it already, or it clashes with another
     */
    public void grant(UUID councilId, UUID userId, RoleGrant grant, String by) {
        CouncilUser user = other(councilId, userId, by);
        if (!grant.role().belongsToCouncil()) {
            throw new InvalidInputException(grant.role() + " is not a council role");
        }
        if (user.holds(grant.role())) {
            throw new ConflictException("the user already holds " + grant.role() + "; revoke it first to change its markets");
        }
        if ((grant.role() == Role.ENFORCEMENT_OFFICER && user.holds(Role.REVENUE_OFFICER))
                || (grant.role() == Role.REVENUE_OFFICER && user.holds(Role.ENFORCEMENT_OFFICER))) {
            throw new ConflictException("one person cannot be both enforcement officer and revenue officer");
        }
        users.grant(councilId, userId, grant, by);
    }

    /** @throws NotFoundException if the user does not hold the role */
    public void revoke(UUID councilId, UUID userId, Role role, String by) {
        other(councilId, userId, by);
        if (!users.revoke(councilId, userId, role, by)) {
            throw new NotFoundException("the user does not hold " + role);
        }
    }

    private CouncilUser other(UUID councilId, UUID userId, String by) {
        if (userId.toString().equals(by)) {
            throw new ConflictException("a user cannot change their own roles");
        }
        return users.findInCouncil(councilId, userId).orElseThrow(() -> new NotFoundException("user not found"));
    }
}
