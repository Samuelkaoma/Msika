package zm.msika.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import zm.msika.api.ApiTest;
import zm.msika.core.user.Permission;
import zm.msika.core.user.Permissions;
import zm.msika.core.user.Role;

/** Several roles per user, market scopes and separation of duties (card M-61). */
class RolesApiTest extends ApiTest {

    record NewUser(String email, String password, String role, Set<UUID> marketIds) {}

    record Grant(String role, Set<UUID> marketIds) {}

    record UserBody(UUID id, List<Grant> roles) {}

    record Market(UUID id) {}

    record NewMarket(String code, String name, String location) {}

    record Stall(UUID id, String code) {}

    record Trader(UUID id) {}

    record NewTrader(String phone, String displayName, String language) {}

    record Allocate(UUID traderId) {}

    record Start(String from) {}

    record Plan(UUID id) {}

    record Me(List<Grant> roles, List<String> permissions) {}

    private static final String PASSWORD = "a-made-up-password-1";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private Fixture council;

    private String base() {
        return "/api/v1/councils/" + council.councilId();
    }

    private UUID user(String role, Set<UUID> markets, String email) {
        var r = api().post(council.adminToken(), base() + "/users", new NewUser(email, PASSWORD, role, markets), UserBody.class);
        assertThat(r.status()).as(role).isEqualTo(201);
        return r.body().id();
    }

    private static String email() {
        return "u-" + UUID.randomUUID() + "@example.test";
    }

    private UUID market(String code) {
        UUID id = api().post(council.adminToken(), base() + "/markets", new NewMarket(code, "Market " + code, "Lusaka"), Market.class)
                .body().id();
        assertThat(api().postCsv(council.adminToken(), base() + "/markets/" + id + "/stalls/import",
                "section,number,class\nA,1,STANDARD\n", String.class).status()).isEqualTo(201);
        return id;
    }

    private int allocate(String token, UUID market, UUID trader) {
        UUID stall = api().get(council.adminToken(), base() + "/markets/" + market + "/stalls", Stall[].class).body()[0].id();
        return api().put(token, base() + "/stalls/" + stall + "/allocation", new Allocate(trader), String.class).status();
    }

    @Test
    @DisplayName("a market master of market A acts in market A and cannot act in market B")
    void marketScope() {
        council = newCouncil();
        UUID a = market("MKA");
        UUID b = market("MKB");
        UUID trader = api().post(council.adminToken(), base() + "/traders", new NewTrader("0977120001", "Scoped", "en"), Trader.class)
                .body().id();
        String email = email();
        user("MARKET_MASTER", Set.of(a), email);
        String master = login(email, PASSWORD);

        assertThat(allocate(master, b, trader)).as("market B").isEqualTo(403);
        assertThat(allocate(master, a, trader)).as("market A").isEqualTo(200);
        assertThat(api().post(master, base() + "/markets/" + b + "/plans", new Start("EMPTY"), String.class).status()).isEqualTo(403);
        UUID planA = api().post(master, base() + "/markets/" + a + "/plans", new Start("GENERATED"), Plan.class).body().id();
        assertThat(api().post(master, base() + "/plans/" + planA + "/publish", null, String.class).status()).isEqualTo(200);
        UUID planB = api().post(council.adminToken(), base() + "/markets/" + b + "/plans", new Start("GENERATED"), Plan.class).body().id();
        assertThat(api().post(master, base() + "/plans/" + planB + "/publish", null, String.class).status())
                .as("the plan's market is looked up, not taken from the path").isEqualTo(403);
        assertThat(api().get(master, base() + "/markets/" + b + "/stalls", String.class).status()).as("reading").isEqualTo(200);
        assertThat(api().post(master, base() + "/markets", new NewMarket("MKC", "No", "Lusaka"), String.class).status()).isEqualTo(403);
        assertThat(api().get(master, "/api/v1/auth/me", Me.class).body().roles()).containsExactly(new Grant("MARKET_MASTER", Set.of(a)));
    }

    @Test
    @DisplayName("an auditor can call every reading endpoint they may, the audit log included, and no changing one")
    void auditor() {
        council = newCouncil();
        UUID m = market("AUD");
        String email = email();
        user("AUDITOR", Set.of(), email);
        String auditor = login(email, PASSWORD);

        assertThat(api().get(auditor, base() + "/audit-log", String.class).body()).contains("market.create");
        assertThat(api().get(council.officerToken(), base() + "/audit-log", String.class).status()).isEqualTo(403);
        assertThat(api().get(auditor, base() + "/reports/arrears", String.class).status()).isEqualTo(200);
        int refused = 0;
        for (String endpoint : EndpointPermissions.listed()) {
            String[] parts = endpoint.split(" ");
            if (parts[0].equals("GET") || EndpointPermissions.isOpen(parts[0], parts[1])) {
                continue;
            }
            String path = parts[1].replace("{councilId}", council.councilId().toString()).replace("{marketId}", m.toString())
                    .replace("{date}", "2026-11-24").replace("{role}", "AUDITOR").replaceAll("\\{\\w+}", UUID.randomUUID().toString());
            var r = parts[0].equals("POST") && path.endsWith("/stalls/import")
                    ? api().postCsv(auditor, path, "section,number,class\nB,1,STANDARD\n", String.class)
                    : api().send(auditor, org.springframework.http.HttpMethod.valueOf(parts[0]), path,
                            parts[0].equals("DELETE") ? null : java.util.Map.of(),
                            parts[0].equals("DELETE") ? null : org.springframework.http.MediaType.APPLICATION_JSON, String.class);
            // The sim console's controller is not switched on here, so its path has no endpoint to reach.
            assertThat(r.status()).as(endpoint).isEqualTo(403);
            refused++;
        }
        assertThat(refused).isGreaterThan(20);
    }

    @Test
    @DisplayName("nobody changes their own roles, in the API or the database")
    void ownRoles() {
        council = newCouncil();
        assertThat(api().post(council.adminToken(), base() + "/users/" + council.adminId() + "/roles", new Grant("AUDITOR", null),
                String.class).status()).isEqualTo(409);
        assertThat(api().delete(council.adminToken(), base() + "/users/" + council.adminId() + "/roles/COUNCIL_ADMIN",
                String.class).status()).isEqualTo(409);
        assertThatThrownBy(() -> ownerJdbc().sql("insert into user_role (id, council_id, user_id, role, granted_by, granted_at) "
                        + "values (gen_random_uuid(), :c, :u, 'AUDITOR', :u::text, now())")
                .param("c", council.councilId()).param("u", council.adminId()).update()).hasMessageContaining("their own roles");
    }

    @Test
    @DisplayName("an enforcement officer cannot be given the revenue role, or the other way round, in the API or the database")
    void separationOfDuties() {
        council = newCouncil();
        UUID m = market("SEP");
        UUID enforcer = user("ENFORCEMENT_OFFICER", Set.of(m), email());
        UUID revenue = user("REVENUE_OFFICER", Set.of(), email());

        assertThat(api().post(council.adminToken(), base() + "/users/" + enforcer + "/roles", new Grant("REVENUE_OFFICER", null),
                String.class).status()).isEqualTo(409);
        assertThat(api().post(council.adminToken(), base() + "/users/" + revenue + "/roles", new Grant("ENFORCEMENT_OFFICER", Set.of(m)),
                String.class).status()).isEqualTo(409);
        assertThatThrownBy(() -> ownerJdbc().sql("insert into user_role (id, council_id, user_id, role, granted_by, granted_at) "
                        + "values (gen_random_uuid(), :c, :u, 'REVENUE_OFFICER', 'test', now())")
                .param("c", council.councilId()).param("u", enforcer).update()).hasMessageContaining("cannot be both");
        // Once the first role is revoked the second may be given, and it takes effect on the next request.
        assertThat(api().delete(council.adminToken(), base() + "/users/" + enforcer + "/roles/ENFORCEMENT_OFFICER", String.class)
                .status()).isEqualTo(200);
        assertThat(api().post(council.adminToken(), base() + "/users/" + enforcer + "/roles", new Grant("REVENUE_OFFICER", null),
                String.class).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("several roles add up; a revoked role stops at once; scoped roles need this council's markets; grants are kept")
    void grants() {
        council = newCouncil();
        UUID m = market("GRA");
        String email = email();
        UUID id = user("COUNCIL_OFFICER", Set.of(), email);
        String token = login(email, PASSWORD);
        assertThat(api().get(token, base() + "/audit-log", String.class).status()).isEqualTo(403);
        api().post(council.adminToken(), base() + "/users/" + id + "/roles", new Grant("AUDITOR", null), String.class);
        assertThat(api().get(token, base() + "/audit-log", String.class).status()).isEqualTo(200);
        api().delete(council.adminToken(), base() + "/users/" + id + "/roles/AUDITOR", String.class);
        assertThat(api().get(token, base() + "/audit-log", String.class).status()).isEqualTo(403);

        assertThat(api().post(council.adminToken(), base() + "/users/" + id + "/roles", new Grant("MARKET_MASTER", null),
                String.class).status()).as("no markets").isEqualTo(400);
        assertThat(api().post(council.adminToken(), base() + "/users/" + id + "/roles", new Grant("AUDITOR", Set.of(m)),
                String.class).status()).as("markets on a council-wide role").isEqualTo(400);
        assertThat(api().post(council.adminToken(), base() + "/users/" + id + "/roles", new Grant("MARKET_MASTER", Set.of(UUID.randomUUID())),
                String.class).status()).as("not this council's market").isEqualTo(409);
        assertThat(api().post(council.adminToken(), base() + "/users/" + id + "/roles", new Grant("MSIKA_OPERATOR", null),
                String.class).status()).isEqualTo(400);
        assertThatThrownBy(() -> ownerJdbc().sql("delete from user_role where user_id = :u").param("u", id).update())
                .hasMessageContaining("only revoked");
        api().delete(council.adminToken(), base() + "/users/" + id + "/roles/COUNCIL_OFFICER", String.class);
        assertThat(api().get(token, base() + "/markets", String.class).status()).as("no roles, no access").isEqualTo(403);
    }

    @Test
    @DisplayName("every endpoint has a permission mapped, and nothing is mapped that does not exist")
    void everyEndpointIsMapped() {
        Set<String> real = new HashSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getName().startsWith("zm.msika.") && method.getBeanType() != NoRuleTestEndpoint.class) {
                info.getMethodsCondition().getMethods().forEach(m -> info.getPatternValues().forEach(p -> real.add(m + " " + p)));
            }
        });
        assertThat(real).hasSizeGreaterThan(50);
        Set<String> unmapped = new HashSet<>(real);
        unmapped.removeAll(EndpointPermissions.listed());
        assertThat(unmapped).as("endpoints with no permission: add them to EndpointPermissions").isEmpty();
        Set<String> stale = new HashSet<>(EndpointPermissions.listed());
        stale.removeAll(real);
        stale.removeIf(e -> e.contains("/api/v1/sim/")); // switched off in this configuration
        assertThat(stale).as("permissions for endpoints that do not exist").isEmpty();
        assertThat(EndpointPermissions.find("GET", "/api/v1/internal/no-rule")).isEmpty();
    }

    @Test
    @DisplayName("the permission matrix is copied into the database on start")
    void seeded() {
        int expected = 0;
        for (Role r : Role.values()) {
            expected += Permissions.of(r).size();
        }
        assertThat(ownerJdbc().sql("select count(*) from role_permission").query(Integer.class).single()).isEqualTo(expected);
        assertThat(Permissions.of(Role.AUDITOR)).containsExactlyInAnyOrder(Permission.READ, Permission.READ_AUDIT);
    }

    @Test
    @DisplayName("the migration moves each existing user's role across")
    void migration() throws Exception {
        String db = "mig" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String server = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/";
        try (var c = DriverManager.getConnection(server + POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var s = c.createStatement()) {
            s.execute("create database " + db + " owner msika_owner");
        }
        Flyway.configure().dataSource(server + db, "msika_owner", "test_owner").target("16").load().migrate();
        JdbcClient old = JdbcClient.create(new DriverManagerDataSource(server + db, "msika_owner", "test_owner"));
        UUID c = UUID.randomUUID();
        old.sql("insert into council (id, name, timezone, created_at) values (:c, 'Old', 'Africa/Lusaka', now())").param("c", c).update();
        for (String role : List.of("COUNCIL_ADMIN", "COUNCIL_OFFICER", "MSIKA_OPERATOR")) {
            old.sql("insert into council_user (id, council_id, email, password_hash, role, active, created_at) "
                            + "values (gen_random_uuid(), :c, :e, 'x', :r, true, now())")
                    .param("c", role.equals("MSIKA_OPERATOR") ? null : c).param("e", role.toLowerCase() + "@example.test").param("r", role)
                    .update();
        }

        Flyway.configure().dataSource(server + db, "msika_owner", "test_owner").load().migrate();

        assertThat(old.sql("select u.email || ' ' || r.role from council_user u join user_role r on r.user_id = u.id order by 1")
                .query(String.class).list())
                .containsExactly("council_admin@example.test COUNCIL_ADMIN", "council_officer@example.test COUNCIL_OFFICER");
        assertThat(old.sql("select count(*) from council_user").query(Integer.class).single()).as("nobody is lost").isEqualTo(3);
    }
}
