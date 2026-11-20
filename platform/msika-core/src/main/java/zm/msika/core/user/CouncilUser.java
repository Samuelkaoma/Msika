package zm.msika.core.user;

import java.time.Instant;
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
 * @param role what they may do
 * @param active false once the user has been deactivated; they can no longer sign in or use a token
 * @param createdAt when the user was created
 */
public record CouncilUser(
        UUID id,
        UUID councilId,
        String email,
        String passwordHash,
        Role role,
        boolean active,
        Instant createdAt) {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    public CouncilUser {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        email = email.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 200 || !EMAIL.matcher(email).matches()) {
            throw new InvalidInputException("email is not valid");
        }
        if (passwordHash.isBlank()) {
            throw new InvalidInputException("passwordHash must not be blank");
        }
        if (role.belongsToCouncil() && councilId == null) {
            throw new InvalidInputException(role + " must belong to a council");
        }
        if (!role.belongsToCouncil() && councilId != null) {
            throw new InvalidInputException(role + " must not belong to a council");
        }
    }

    /** The same user, deactivated or reactivated. */
    public CouncilUser withActive(boolean newActive) {
        return new CouncilUser(id, councilId, email, passwordHash, role, newActive, createdAt);
    }

    /** Never prints the password hash. */
    @Override
    public String toString() {
        return "CouncilUser[id=" + id + ", email=" + email + ", role=" + role + ", active=" + active + "]";
    }
}
