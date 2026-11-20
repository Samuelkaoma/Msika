package zm.msika.api;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** A tiny HTTP client for tests: sends a request with an optional bearer token and returns status and body. */
public final class ApiClient {

    /** The HTTP status and the body read as the requested type (null for no body or for {@code Void}). */
    public record Result<T>(int status, T body) {}

    private final RestClient client;

    public ApiClient(int port) {
        this.client = RestClient.create("http://localhost:" + port);
    }

    public <T> Result<T> send(
            String token, HttpMethod method, String path, Object body, MediaType type, Class<T> responseType) {
        return send(token, method, path, body, type, responseType, null);
    }

    /** Like the above, with an {@code Idempotency-Key} header when {@code idempotencyKey} is not null. */
    public <T> Result<T> send(String token, HttpMethod method, String path, Object body, MediaType type,
            Class<T> responseType, String idempotencyKey) {
        RestClient.RequestBodySpec spec = client.method(method).uri(path);
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        if (token != null) {
            spec = spec.headers(h -> h.setBearerAuth(token));
        }
        if (body != null) {
            spec = spec.contentType(type).body(body);
        }
        return spec.exchange((request, response) -> new Result<>(
                response.getStatusCode().value(),
                responseType == Void.class ? null : response.bodyTo(responseType)));
    }

    public <T> Result<T> get(String token, String path, Class<T> responseType) {
        return send(token, HttpMethod.GET, path, null, null, responseType);
    }

    public <T> Result<T> post(String token, String path, Object body, Class<T> responseType) {
        return send(token, HttpMethod.POST, path, body, MediaType.APPLICATION_JSON, responseType);
    }

    public <T> Result<T> put(String token, String path, Object body, Class<T> responseType) {
        return send(token, HttpMethod.PUT, path, body, MediaType.APPLICATION_JSON, responseType);
    }

    public <T> Result<T> delete(String token, String path, Class<T> responseType) {
        return send(token, HttpMethod.DELETE, path, null, null, responseType);
    }

    /** Uploads a CSV file as the request body, as UTF-8 text/csv. */
    public <T> Result<T> postCsv(String token, String path, String csv, Class<T> responseType) {
        return send(token, HttpMethod.POST, path, csv.getBytes(StandardCharsets.UTF_8),
                MediaType.parseMediaType("text/csv"), responseType);
    }
}
