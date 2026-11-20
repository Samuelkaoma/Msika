package zm.msika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** AGENTS.md rule 4: the app role owns nothing, and can never bypass row-level security. */
class DatabaseRolesTest extends PostgresIntegrationTest {

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
        Integer notOwner = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where installed_by <> 'msika_owner'", Integer.class);
        assertThat(notOwner).isZero();
    }
}
