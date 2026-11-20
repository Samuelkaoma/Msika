package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Boots the whole app against a real Postgres (Testcontainers) set up by the same script as compose. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

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
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/zamsim";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "zamsim");
        registry.add("spring.datasource.password", () -> "test_zamsim");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "zamsim");
        registry.add("spring.flyway.password", () -> "test_zamsim");
    }

    @LocalServerPort
    int port;

    @Test
    void healthAnswersUpAndReportsTheDatabase() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body()).contains("\"db\":{\"status\":\"UP\"}");
    }
}
