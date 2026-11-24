package zm.msika.api;

import java.util.UUID;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for tests that call the real HTTP API as signed-in users. Every endpoint needs a token, so this
 * signs in as the bootstrap operator and builds councils with an admin and an officer on demand.
 * Each call to {@link #newCouncil()} makes a fresh council, so tests never depend on each other.
 */
public abstract class ApiTest extends PostgresIntegrationTest {

    /** The password of every user these helpers create. Made up, test only. */
    public static final String USER_PASSWORD = "correct horse battery";

    public record LoginBody(String token, String tokenType, String expiresAt) {}

    public record CouncilBody(UUID id, String name, String timezone) {}

    public record UserBody(UUID id, UUID councilId, String email, boolean active) {}

    /** A council with a signed-in admin and officer. */
    public record Fixture(UUID councilId, String adminEmail, String adminToken, UUID adminId,
            String officerEmail, String officerToken, UUID officerId) {}

    @LocalServerPort
    protected int port;

    protected ApiClient api() {
        return new ApiClient(port);
    }

    private String operatorToken;

    /** A token for the bootstrap operator, who may do anything. Signed in once per test instance. */
    protected String operatorToken() {
        if (operatorToken == null) {
            operatorToken = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        }
        return operatorToken;
    }

    /** Signs in and returns the token, failing the test if sign-in is refused. */
    protected String login(String email, String password) {
        ApiClient.Result<LoginBody> result = api().post(null, "/api/v1/auth/login",
                new LoginRequest(email, password), LoginBody.class);
        assertThat(result.status()).as("login of " + email).isEqualTo(200);
        return result.body().token();
    }

    public record LoginRequest(String email, String password) {}

    public record CreateUserRequest(String email, String password, String role) {}

    public record CreateCouncilRequest(String name, String timezone) {}

    /** Creates a new council (as the operator) with one admin and one officer, both signed in. */
    protected Fixture newCouncil() {
        ApiClient.Result<CouncilBody> council = api().post(operatorToken(), "/api/v1/councils",
                new CreateCouncilRequest("Test Council " + UUID.randomUUID(), null), CouncilBody.class);
        assertThat(council.status()).isEqualTo(201);
        UUID councilId = council.body().id();

        String suffix = councilId.toString().substring(0, 8);
        String adminEmail = "admin-" + suffix + "@example.test";
        String officerEmail = "officer-" + suffix + "@example.test";
        ApiClient.Result<UserBody> admin = api().post(operatorToken(), "/api/v1/councils/" + councilId + "/users",
                new CreateUserRequest(adminEmail, USER_PASSWORD, "COUNCIL_ADMIN"), UserBody.class);
        ApiClient.Result<UserBody> officer = api().post(operatorToken(), "/api/v1/councils/" + councilId + "/users",
                new CreateUserRequest(officerEmail, USER_PASSWORD, "COUNCIL_OFFICER"), UserBody.class);
        assertThat(admin.status()).isEqualTo(201);
        assertThat(officer.status()).isEqualTo(201);

        return new Fixture(councilId,
                adminEmail, login(adminEmail, USER_PASSWORD), admin.body().id(),
                officerEmail, login(officerEmail, USER_PASSWORD), officer.body().id());
    }

    /** Convenience for tests that need the verb as a constant. */
    protected static final HttpMethod GET = HttpMethod.GET;
}
