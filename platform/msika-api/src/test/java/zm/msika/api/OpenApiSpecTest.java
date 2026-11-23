package zm.msika.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI description of the API (card M-26) is committed at {@code docs/api/openapi.json}, and the dashboard's
 * typed client is generated from that file. This test fails if the code and the committed file disagree, so a change
 * to an endpoint cannot reach the dashboard unnoticed.
 *
 * <p>To update the file after changing the API: {@code ./gradlew :msika-api:integrationTest --tests '*OpenApiSpecTest'
 * -PupdateOpenApi}, then {@code pnpm --filter council-web gen:api}.
 */
class OpenApiSpecTest extends ApiTest {

    static final Path COMMITTED = Path.of("../../docs/api/openapi.json");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    @DisplayName("the committed OpenAPI description matches the code")
    void specIsCurrent() throws Exception {
        ApiClient.Result<String> r = api().get(null, "/v3/api-docs", String.class);
        assertThat(r.status()).isEqualTo(200);
        JsonNode spec = JSON.readTree(r.body());
        // The server address depends on the random test port: leave it out.
        ((tools.jackson.databind.node.ObjectNode) spec).remove("servers");
        // Endpoints that exist only in test sources are not part of the API.
        ((tools.jackson.databind.node.ObjectNode) spec.path("paths")).remove("/api/v1/internal/no-rule");
        // The pretty printer uses the platform's line ending; git keeps the file with \n. Compare and write with \n.
        String current = JSON.writeValueAsString(JSON.treeToValue(spec, Object.class)).replace("\r\n", "\n") + "\n";

        assertThat(spec.path("paths").has("/api/v1/auth/login")).isTrue();
        if (Boolean.getBoolean("msika.updateOpenApi")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, current, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COMMITTED)).as(COMMITTED + " exists (run with -PupdateOpenApi to create it)").isTrue();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("docs/api/openapi.json is out of date: run ./gradlew :msika-api:integrationTest "
                        + "--tests '*OpenApiSpecTest' -PupdateOpenApi, then pnpm --filter council-web gen:api")
                .isEqualTo(current);
    }
}
