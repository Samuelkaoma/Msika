package zm.msika.core.user;

import zm.msika.core.common.InvalidInputException;

/** What a new password must look like. Sign-in does not apply it, so a policy change never locks anyone out. */
public final class PasswordPolicy {

    /** At least this many characters. Length matters more than odd symbols. */
    public static final int MIN_LENGTH = 12;

    /** BCrypt ignores everything after 72 bytes, so longer passwords are refused rather than silently cut. */
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {}

    /** @throws InvalidInputException if the password is too short, too long or blank */
    public static void check(String password) {
        if (password == null || password.isBlank()) {
            throw new InvalidInputException("password is required");
        }
        if (password.length() < MIN_LENGTH) {
            throw new InvalidInputException("password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LENGTH) {
            throw new InvalidInputException("password must be at most " + MAX_LENGTH + " bytes");
        }
    }
}
