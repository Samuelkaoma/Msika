package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Boots the whole app against a real Postgres and checks /health reports the database as up. */
class HealthEndpointTest extends ZamsimIntegrationTest {

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
