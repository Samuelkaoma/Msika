package zm.msika.api;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for integration tests: boots the whole app on a random port against a real Postgres.
 *
 * <p>The database is started once for the whole test run and shared. It is set up by the same script
 * as docker compose (two roles: the owner runs migrations, the app role owns nothing). Tests must
 * not depend on an empty database: each one creates its own council and works only inside it.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../infra/postgres/init/01-init.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-init.sh")
            .withEnv("MSIKA_OWNER_PASSWORD", "test_owner")
            .withEnv("MSIKA_APP_PASSWORD", "test_app")
            .withEnv("ZAMSIM_PASSWORD", "test_zamsim")
            .withEnv("ELEVYSIM_PASSWORD", "test_elevysim");

    static {
        // Started once and left to the Testcontainers cleanup process when the JVM exits.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/msika";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.password", () -> "test_owner");
        // Made-up test credentials for the bootstrap operator. Cheap BCrypt keeps the tests quick.
        registry.add("msika.bootstrap-operator.email", () -> OPERATOR_EMAIL);
        registry.add("msika.bootstrap-operator.password", () -> OPERATOR_PASSWORD);
        registry.add("msika.auth.bcrypt-strength", () -> "4");
        // The scheduled day close is off in tests (tests call the service directly), and the seal signing key
        // goes in a throwaway folder.
        registry.add("msika.day-close.enabled", () -> "false");
        registry.add("msika.reconciliation.enabled", () -> "false");
        registry.add("msika.sms.workers.enabled", () -> "false");
        registry.add("msika.levy-charge.enabled", () -> "false");
        registry.add("msika.levy-sweep.enabled", () -> "false");
        registry.add("msika.levy-receipts.enabled", () -> "false");
        registry.add("msika.sms.gateway-secret", () -> "test-only-sms-gateway-secret");
        registry.add("msika.seal.key-file", () -> SEAL_KEY_FILE.toString());
        registry.add("msika.payments.callback-secret", () -> CALLBACK_SECRET);
    }

    /** The secret callbacks are signed with in tests. Made up, test only. */
    public static final String CALLBACK_SECRET = "test-only-callback-secret-0001";

    /** Where the test app keeps its (throwaway) seal signing key. */
    public static final java.nio.file.Path SEAL_KEY_FILE = tempKeyFile();

    private static java.nio.file.Path tempKeyFile() {
        try {
            return java.nio.file.Files.createTempDirectory("msika-test-keys").resolve("seal-key.properties");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The database as the OWNER role, which row-level security does not apply to. Tests use it to simulate tampering. */
    public static org.springframework.jdbc.core.simple.JdbcClient ownerJdbc() {
        return org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/msika",
                "msika_owner", "test_owner"));
    }

    /** The JDBC URL of the msika database, for tests of the command-line verifier. */
    public static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/msika";
    }

    /** The operator the app creates at start-up in tests. Made up, test only. */
    public static final String OPERATOR_EMAIL = "operator@example.test";

    public static final String OPERATOR_PASSWORD = "operator test password";
}
