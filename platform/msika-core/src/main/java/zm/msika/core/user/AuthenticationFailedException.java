package zm.msika.core.user;

/**
 * Sign-in failed. The message is always the same, whether the email is unknown, the password is wrong
 * or the user is deactivated, so an attacker learns nothing about which emails exist. The API answers
 * 401.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException() {
        super("invalid email or password");
    }
}
