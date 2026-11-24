package zm.msika.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;

/**
 * Sign-in, tokens, roles and default-deny, through the real HTTP API (ARCHITECTURE.md section 9).
 * Spring Security answers 401 for "we do not know who you are" and 403 for "we know, and no".
 */
class AuthApiTest extends ApiTest {

    record GrantBody(String role) {}

    record MeBody(UUID userId, UUID councilId, java.util.List<GrantBody> roles, String email) {}

    record ErrorBody(String error, String message) {}

    record MarketRequest(String code, String name, String location) {}

    record ActiveRequest(Boolean active) {}

    record AllocateRequest(UUID traderId) {}

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    private static String councilPath(UUID council) {
        return "/api/v1/councils/" + council;
    }

    private int marketsStatus(String token, UUID council) {
        return api().get(token, councilPath(council) + "/markets", Void.class).status();
    }

    @Test
    @DisplayName("sign in, then ask who you are; the token carries no role")
    void signInAndMe() {
        Fixture council = newCouncil();

        Result<LoginBody> login = api().post(null, "/api/v1/auth/login",
                new LoginRequest(council.adminEmail(), USER_PASSWORD), LoginBody.class);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().tokenType()).isEqualTo("Bearer");
        assertThat(Instant.parse(login.body().expiresAt())).isAfter(Instant.now());

        Result<MeBody> me = api().get(login.body().token(), "/api/v1/auth/me", MeBody.class);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().userId()).isEqualTo(council.adminId());
        assertThat(me.body().councilId()).isEqualTo(council.councilId());
        assertThat(me.body().roles()).containsExactly(new GrantBody("COUNCIL_ADMIN"));

        String payload = new String(Base64.getUrlDecoder().decode(login.body().token().split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertThat(payload).as("the role is read from the database, never trusted from the token")
                .doesNotContain("role").doesNotContain("COUNCIL_ADMIN");
    }

    @Test
    @DisplayName("a wrong password is refused, and an unknown email gets exactly the same answer")
    void wrongPasswordAndUnknownEmailLookIdentical() {
        Fixture council = newCouncil();

        Result<ErrorBody> wrongPassword = api().post(null, "/api/v1/auth/login",
                new LoginRequest(council.adminEmail(), "not the password!!"), ErrorBody.class);
        Result<ErrorBody> unknownEmail = api().post(null, "/api/v1/auth/login",
                new LoginRequest("nobody@example.test", USER_PASSWORD), ErrorBody.class);

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(unknownEmail.body()).as("no hint about which part was wrong").isEqualTo(wrongPassword.body());
        assertThat(wrongPassword.body().message()).isEqualTo("invalid email or password");
    }

    @Test
    @DisplayName("a missing email or password is refused with the same 401")
    void missingCredentials() {
        assertThat(api().post(null, "/api/v1/auth/login", new LoginRequest(null, null), Void.class).status())
                .isEqualTo(401);
        assertThat(api().post(null, "/api/v1/auth/login", new LoginRequest("a@example.test", null), Void.class).status())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a deactivated user is refused on their very next request, even with a token that has not expired")
    void deactivatedUserRefusedImmediately() {
        Fixture council = newCouncil();
        UUID id = council.councilId();
        assertThat(marketsStatus(council.officerToken(), id)).as("before").isEqualTo(200);

        Result<UserBody> deactivated = api().put(council.adminToken(),
                councilPath(id) + "/users/" + council.officerId() + "/active", new ActiveRequest(false), UserBody.class);
        assertThat(deactivated.status()).isEqualTo(200);
        assertThat(deactivated.body().active()).isFalse();

        assertThat(marketsStatus(council.officerToken(), id))
                .as("the same token, one request later").isEqualTo(401);
        assertThat(api().post(null, "/api/v1/auth/login",
                new LoginRequest(council.officerEmail(), USER_PASSWORD), Void.class).status())
                .as("and they cannot sign in again").isEqualTo(401);

        api().put(council.adminToken(), councilPath(id) + "/users/" + council.officerId() + "/active",
                new ActiveRequest(true), UserBody.class);
        assertThat(marketsStatus(council.officerToken(), id)).as("reactivated").isEqualTo(200);
    }

    @Test
    @DisplayName("an officer can read but gets 403 on anything that changes data")
    void officerIsReadOnly() {
        Fixture council = newCouncil();
        String officer = council.officerToken();
        String base = councilPath(council.councilId());

        assertThat(marketsStatus(officer, council.councilId())).isEqualTo(200);

        assertThat(api().post(officer, base + "/markets", new MarketRequest("OFF", "Officer Market", ""), Void.class)
                .status()).as("create a market").isEqualTo(403);
        assertThat(api().postCsv(officer, base + "/markets/" + UUID.randomUUID() + "/stalls/import",
                "section,number,class\nA,1,STANDARD\n", Void.class).status()).as("import stalls").isEqualTo(403);
        assertThat(api().put(officer, base + "/stalls/" + UUID.randomUUID() + "/allocation",
                new AllocateRequest(UUID.randomUUID()), Void.class).status()).as("allocate").isEqualTo(403);
        assertThat(api().delete(officer, base + "/stalls/" + UUID.randomUUID() + "/allocation", Void.class)
                .status()).as("free").isEqualTo(403);
        assertThat(api().post(officer, base + "/users",
                new CreateUserRequest("x@example.test", USER_PASSWORD, "COUNCIL_ADMIN"), Void.class).status())
                .as("create a user").isEqualTo(403);
        assertThat(api().put(officer, base + "/users/" + council.adminId() + "/active", new ActiveRequest(false),
                Void.class).status()).as("deactivate the admin").isEqualTo(403);
    }

    @Test
    @DisplayName("an endpoint nobody wrote a rule for answers 403 to everyone, even the operator")
    void endpointWithNoRuleIsDenied() {
        Fixture council = newCouncil();
        String path = "/api/v1/internal/no-rule";

        assertThat(api().get(council.adminToken(), path, Void.class).status()).as("admin").isEqualTo(403);
        assertThat(api().get(council.officerToken(), path, Void.class).status()).as("officer").isEqualTo(403);
        assertThat(api().get(operatorToken(), path, Void.class).status()).as("operator").isEqualTo(403);
        assertThat(api().get(null, path, Void.class).status()).as("nobody signed in").isEqualTo(401);
        assertThat(api().get(council.adminToken(), "/api/v1/does-not-exist", Void.class).status())
                .as("a path that does not exist is denied, not 404").isEqualTo(403);
    }

    @Test
    @DisplayName("an admin cannot read or change another council, but can use their own")
    void councilsAreScopedToTheirUsers() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();

        assertThat(marketsStatus(a.adminToken(), a.councilId())).isEqualTo(200);
        assertThat(marketsStatus(a.adminToken(), b.councilId())).as("read the other council").isEqualTo(403);
        assertThat(api().post(a.adminToken(), councilPath(b.councilId()) + "/markets",
                new MarketRequest("EVL", "Evil Market", ""), Void.class).status())
                .as("write to the other council").isEqualTo(403);
        assertThat(api().post(a.adminToken(), councilPath(b.councilId()) + "/users",
                new CreateUserRequest("evil@example.test", USER_PASSWORD, "COUNCIL_ADMIN"), Void.class).status())
                .as("add a user to the other council").isEqualTo(403);
        assertThat(marketsStatus(b.officerToken(), a.councilId())).as("an officer of B reading A").isEqualTo(403);
    }

    @Test
    @DisplayName("only the operator can create a council")
    void onlyOperatorCreatesCouncils() {
        Fixture council = newCouncil();
        CreateCouncilRequest request = new CreateCouncilRequest("Another Council", null);

        assertThat(api().post(council.adminToken(), "/api/v1/councils", request, Void.class).status()).isEqualTo(403);
        assertThat(api().post(council.officerToken(), "/api/v1/councils", request, Void.class).status()).isEqualTo(403);
        assertThat(api().post(null, "/api/v1/councils", request, Void.class).status()).isEqualTo(401);
        assertThat(api().post(operatorToken(), "/api/v1/councils", request, Void.class).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("no token, a garbage token, an expired token, an unknown user and a forged token are all 401")
    void badTokensAreRefused() {
        Fixture council = newCouncil();
        UUID id = council.councilId();
        CouncilUser admin = new CouncilUser(council.adminId(), id, council.adminEmail(), "x",
                Role.COUNCIL_ADMIN, true, Instant.now());

        assertThat(marketsStatus(null, id)).as("no token").isEqualTo(401);
        assertThat(marketsStatus("garbage", id)).as("garbage").isEqualTo(401);
        assertThat(marketsStatus(tokens.issue(admin, Duration.ofMinutes(-5)).value(), id))
                .as("expired five minutes ago").isEqualTo(401);

        CouncilUser nobody = new CouncilUser(UUID.randomUUID(), id, "ghost@example.test", "x",
                Role.COUNCIL_ADMIN, true, Instant.now());
        assertThat(marketsStatus(tokens.issue(nobody).value(), id))
                .as("a genuine token for a user that does not exist").isEqualTo(401);

        byte[] otherSecret = new byte[32];
        new java.security.SecureRandom().nextBytes(otherSecret);
        NimbusJwtEncoder attacker = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(otherSecret, "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("msika").subject(council.adminId().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        String forged = attacker.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        assertThat(marketsStatus(forged, id)).as("signed with a key that is not ours").isEqualTo(401);

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"msika\",\"sub\":\"" + council.adminId() + "\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(marketsStatus(header + "." + payload + ".", id)).as("alg none").isEqualTo(401);
    }

    @Test
    @DisplayName("passwords are stored only as BCrypt hashes, and never sent back")
    void passwordsAreNeverStoredOrReturned() {
        Fixture council = newCouncil();
        String email = "carol-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";

        Result<String> created = api().post(council.adminToken(), councilPath(council.councilId()) + "/users",
                new CreateUserRequest(email, USER_PASSWORD, "COUNCIL_OFFICER"), String.class);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().toLowerCase()).doesNotContain("password").doesNotContain("hash")
                .doesNotContain(USER_PASSWORD);
        String stored = jdbc.sql("select password_hash from auth_find_user_by_email(:email)")
                .param("email", email).query(String.class).single();
        assertThat(stored).startsWith("$2").hasSize(60).doesNotContain(USER_PASSWORD);
    }

    @Test
    @DisplayName("user creation rules: duplicate email, weak password, bad role")
    void userCreationRules() {
        Fixture council = newCouncil();
        String path = councilPath(council.councilId()) + "/users";
        String token = council.adminToken();

        assertThat(api().post(token, path, new CreateUserRequest(council.officerEmail().toUpperCase(), USER_PASSWORD,
                "COUNCIL_OFFICER"), Void.class).status()).as("same email in any case").isEqualTo(409);
        assertThat(api().post(token, path, new CreateUserRequest("weak@example.test", "short", "COUNCIL_OFFICER"),
                Void.class).status()).as("password too short").isEqualTo(400);
        assertThat(api().post(token, path, new CreateUserRequest("op@example.test", USER_PASSWORD, "MSIKA_OPERATOR"),
                Void.class).status()).as("an operator cannot be created here").isEqualTo(400);
        assertThat(api().post(token, path, new CreateUserRequest("who@example.test", USER_PASSWORD, "SUPERUSER"),
                Void.class).status()).as("unknown role").isEqualTo(400);
        assertThat(api().post(token, path, new CreateUserRequest("not an email", USER_PASSWORD, "COUNCIL_OFFICER"),
                Void.class).status()).as("bad email").isEqualTo(400);
    }

    @Test
    @DisplayName("health stays open without signing in")
    void healthIsOpen() {
        assertThat(api().get(null, "/health", Void.class).status()).isEqualTo(200);
        assertThat(List.of(api().get(null, "/actuator/health", Void.class).status())).containsAnyOf(403, 401);
    }
}
