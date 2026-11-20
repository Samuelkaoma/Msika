package zm.msika.core.user;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;

/**
 * Use case: create a council admin or officer. Operators are not created here: they have no council
 * and are set up outside the API.
 */
public final class CreateUser {

    private final CouncilRepository councils;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final Clock clock;

    public CreateUser(CouncilRepository councils, UserRepository users, PasswordHasher hasher, Clock clock) {
        this.councils = Objects.requireNonNull(councils);
        this.users = Objects.requireNonNull(users);
        this.hasher = Objects.requireNonNull(hasher);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @throws NotFoundException if the council does not exist
     * @throws InvalidInputException if the role is not a council role, or the email or password is bad
     * @throws ConflictException if the email is already used
     */
    public CouncilUser execute(UUID councilId, String email, String password, Role role) {
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        if (role == null || !role.belongsToCouncil()) {
            throw new InvalidInputException("role must be COUNCIL_ADMIN or COUNCIL_OFFICER");
        }
        PasswordPolicy.check(password);
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalised.isEmpty() && users.findByEmail(normalised).isPresent()) {
            throw new ConflictException("a user with this email already exists");
        }
        CouncilUser user = new CouncilUser(
                UUID.randomUUID(), councilId, normalised, hasher.hash(password), role, true, clock.instant());
        users.add(user);
        return user;
    }
}
