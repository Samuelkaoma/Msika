package zm.msika.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import zm.msika.core.user.PasswordHasher;

/** Hashes passwords with BCrypt: salted and deliberately slow, so guessing is expensive. */
@Component
class BcryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    /** @param strength BCrypt cost, 4 to 31. Each +1 doubles the time. 12 is about a quarter of a second. */
    BcryptPasswordHasher(@Value("${msika.auth.bcrypt-strength:12}") int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hash) {
        return encoder.matches(rawPassword, hash);
    }
}
