package zm.msika.core.user;

/** Hashes and checks passwords. Implemented in msika-api with BCrypt. */
public interface PasswordHasher {

    /** A salted, slow hash of the password. */
    String hash(String rawPassword);

    /** True if the password matches the hash. Takes about as long whether it matches or not. */
    boolean matches(String rawPassword, String hash);
}
