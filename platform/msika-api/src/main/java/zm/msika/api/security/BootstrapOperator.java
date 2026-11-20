package zm.msika.api.security;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import zm.msika.core.user.PasswordHasher;
import zm.msika.core.user.PasswordPolicy;

/**
 * Creates the first Msika operator, who creates councils, from two settings:
 * {@code msika.bootstrap-operator.email} and {@code msika.bootstrap-operator.password}.
 *
 * <p>Nothing is created unless both are set, and nothing happens if that email already exists. No
 * default is built in: the repository is public, so a default password would be a published one.
 * Set them from the environment (see infra/.env.example), then remove the password setting.
 */
@Component
class BootstrapOperator implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapOperator.class);

    private final String email;
    private final String password;
    private final PasswordHasher hasher;
    private final JdbcClient jdbc;
    private final Clock clock;

    BootstrapOperator(
            @Value("${msika.bootstrap-operator.email:}") String email,
            @Value("${msika.bootstrap-operator.password:}") String password,
            PasswordHasher hasher,
            JdbcClient jdbc,
            Clock clock) {
        this.email = email;
        this.password = password;
        this.hasher = hasher;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        PasswordPolicy.check(password);
        Boolean created = jdbc.sql("select auth_create_operator(:id, :email, :hash, :createdAt)")
                .param("id", UUID.randomUUID())
                .param("email", email.trim())
                .param("hash", hasher.hash(password))
                .param("createdAt", OffsetDateTime.now(clock))
                .query(Boolean.class)
                .single();
        LOG.info("bootstrap operator {}", Boolean.TRUE.equals(created) ? "created" : "already exists");
    }
}
