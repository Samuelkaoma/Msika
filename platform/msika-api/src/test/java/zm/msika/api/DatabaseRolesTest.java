package zm.msika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** AGENTS.md rule 4: the app role owns nothing, and can never bypass row-level security. */
@Tag("integration")
@Testcontainers
@SpringBootTest
class DatabaseRolesTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../infra/postgres/init/01-init.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-init.sh")
            .withEnv("MSIKA_OWNER_PASSWORD", "test_owner")
            .withEnv("MSIKA_APP_PASSWORD", "test_app")
            .withEnv("ZAMSIM_PASSWORD", "test_zamsim")
            .withEnv("ELEVYSIM_PASSWORD", "test_elevysim");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/msika";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.password", () -> "test_owner");
    }

    @Autowired
    JdbcTemplate jdbc; // connected as msika_app, like the running app

    @Test
    void appConnectsAsTheAppRole() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("msika_app");
    }

    @Test
    void appRoleIsNotASuperuserAndCannotBypassRowLevelSecurity() {
        Boolean unsafe = jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname = 'msika_app'", Boolean.class);
        assertThat(unsafe).isFalse();
    }

    @Test
    void appRoleOwnsNoTables() {
        Integer owned = jdbc.queryForObject(
                "select count(*) from pg_tables where tableowner = 'msika_app'", Integer.class);
        assertThat(owned).isZero();
    }

    @Test
    void appRoleCannotCreateTables() {
        assertThatThrownBy(() -> jdbc.execute("create table should_not_exist (id int)"))
                .rootCause()
                .hasMessageContaining("permission denied for schema public");
    }

    @Test
    void migrationsRanAsTheOwner() {
        String ran = jdbc.queryForObject(
                "select installed_by from flyway_schema_history where version = '1'", String.class);
        assertThat(ran).isEqualTo("msika_owner");
    }
}
