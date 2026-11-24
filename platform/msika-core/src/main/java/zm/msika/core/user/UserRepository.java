package zm.msika.core.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where users are kept. Implemented in msika-api. */
public interface UserRepository {

    /** Adds a council user with their roles. Operators cannot be added here. */
    void add(CouncilUser user, String grantedBy);

    void grant(UUID councilId, UUID userId, RoleGrant grant, String by);

    /** @return false if the user does not hold the role */
    boolean revoke(UUID councilId, UUID userId, Role role, String by);

    /** The council's users, by email. */
    List<CouncilUser> list(UUID councilId);

    /**
     * Finds a user by sign-in email in ANY council, because the sign-in form does not say which
     * council the person belongs to. Returns the user whether or not they are active.
     */
    Optional<CouncilUser> findByEmail(String email);

    /**
     * Finds a user by id in any council. Called on every request to re-check the user, so a
     * deactivation takes effect at once.
     */
    Optional<CouncilUser> findById(UUID id);

    /** Finds a user of this council. */
    Optional<CouncilUser> findInCouncil(UUID councilId, UUID userId);

    void setActive(UUID councilId, UUID userId, boolean active);
}
