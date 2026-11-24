package zm.msika.core.user;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import zm.msika.core.common.InvalidInputException;

/**
 * A person who signs in to the council dashboard.
 *
 * @param id the user's id
 * @param councilId the council they belong to, or {@code null} for an operator, who belongs to none
 * @param email their sign-in name, stored in lower case
 * @param passwordHash a salted hash of the password (never the password itself)
 * @param grants the roles they hold (an operator holds exactly MSIKA_OPERATOR)
 * @param active false once the user has been deactivated; they can no longer sign in or use a token
 * @param createdAt when the user was created
 */
public record CouncilUser(
        UUID id,
        UUID councilId,
        String email,
        String passwordHash,
        List<RoleGrant> grants,
        boolean active,
        Instant createdAt) {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    public CouncilUser {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        grants = List.copyOf(grants);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        email = email.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 200 || !EMAIL.matcher(email).matches()) {
            throw new InvalidInputException("email is not valid");
        }
        if (passwordHash.isBlank()) {
            throw new InvalidInputException("passwordHash must not be blank");
        }
        for (RoleGrant g : grants) {
            if (g.role().belongsToCouncil() != (councilId != null)) {
                throw new InvalidInputException(g.role() + (councilId == null ? " must belong to a council"
                        : " must not belong to a council"));
            }
        }
    }

    /** A user with one council-wide role. */
    public CouncilUser(UUID id, UUID councilId, String email, String passwordHash, Role role, boolean active, Instant createdAt) {
        this(id, councilId, email, passwordHash, List.of(RoleGrant.councilWide(role)), active, createdAt);
    }

    public boolean holds(Role role) {
        return grants.stream().anyMatch(g -> g.role() == role);
    }

    /** The same user, deactivated or reactivated. */
    public CouncilUser withActive(boolean newActive) {
        return new CouncilUser(id, councilId, email, passwordHash, grants, newActive, createdAt);
    }

    /** Never prints the password hash. */
    @Override
    public String toString() {
        return "CouncilUser[id=" + id + ", email=" + email + ", roles=" + grants.stream().map(RoleGrant::role).toList() + ", active=" + active + "]";
    }
}
