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

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7")
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
    }
}
