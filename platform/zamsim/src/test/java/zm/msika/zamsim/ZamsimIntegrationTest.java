package zm.msika.zamsim;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for zamsim integration tests: boots the whole app on a random port against a real Postgres, set
 * up by the same script as docker compose. The database is started once and shared, so each test makes
 * its own wallets and never relies on an empty database.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ZamsimIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../infra/postgres/init/01-init.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-init.sh")
            .withEnv("MSIKA_OWNER_PASSWORD", "test_owner")
            .withEnv("MSIKA_APP_PASSWORD", "test_app")
            .withEnv("ZAMSIM_PASSWORD", "test_zamsim")
            .withEnv("ELEVYSIM_PASSWORD", "test_elevysim");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/zamsim");
        registry.add("spring.datasource.password", () -> "test_zamsim");
    }
}
