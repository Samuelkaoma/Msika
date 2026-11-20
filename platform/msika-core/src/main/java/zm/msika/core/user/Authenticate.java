package zm.msika.core.user;

import java.util.Objects;
import java.util.Optional;

/**
 * Use case: check an email and password.
 *
 * <p>An unknown email still costs one password hash check against a throwaway hash, so how long the
 * answer takes does not reveal whether the email exists.
 */
public final class Authenticate {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final String throwawayHash;

    public Authenticate(UserRepository users, PasswordHasher hasher) {
        this.users = Objects.requireNonNull(users);
        this.hasher = Objects.requireNonNull(hasher);
        this.throwawayHash = hasher.hash("not-a-real-password-just-to-spend-time");
    }

    /** @throws AuthenticationFailedException for any reason the person may not sign in */
    public CouncilUser execute(String email, String password) {
        if (email == null || password == null) {
            hasher.matches("x", throwawayHash);
            throw new AuthenticationFailedException();
        }
        Optional<CouncilUser> found = users.findByEmail(email.trim());
        boolean passwordOk = hasher.matches(password, found.map(CouncilUser::passwordHash).orElse(throwawayHash));
        if (found.isEmpty() || !passwordOk || !found.get().active()) {
            throw new AuthenticationFailedException();
        }
        return found.get();
    }
}
