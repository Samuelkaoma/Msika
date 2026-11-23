package zm.msika.api.levy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import zm.msika.core.levy.LevyAuthorityPort;
import zm.msika.core.levy.LevyAuthorityUnavailableException;

/**
 * Talks to the simulated eLevy over HTTP (AGENTS.md rule 6): it knows elevysim's public HTTP shape and nothing of its
 * code. The real eLevy will be another class like this one.
 */
final class ElevysimLevyAdapter implements LevyAuthorityPort {

    private final RestClient client;

    ElevysimLevyAdapter(RestClient client) {
        this.client = client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String openAccount(String councilRef, String marketRef, String traderRef, long dailyLevyNgwee) {
        try {
            List<Map<String, Object>> found = client.get()
                    .uri(u -> u.path("/v1/levy-accounts").queryParam("market", marketRef).queryParam("trader", traderRef).build())
                    .retrieve().body(List.class);
            if (found != null && !found.isEmpty()) {
                return found.get(0).get("id").toString();
            }
            Map<String, Object> opened = client.post().uri("/v1/levy-accounts").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("councilRef", councilRef, "marketRef", marketRef, "traderRef", traderRef,
                            "dailyLevyNgwee", dailyLevyNgwee))
                    .retrieve().body(Map.class);
            if (opened == null || opened.get("id") == null) {
                throw new LevyAuthorityUnavailableException("the levy authority opened an account without an id", null);
            }
            return opened.get("id").toString();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                // Opened by a call that raced this one: look it up again.
                return openAccount(councilRef, marketRef, traderRef, dailyLevyNgwee);
            }
            throw new LevyAuthorityUnavailableException("the levy authority refused to open the account", e);
        } catch (RestClientException e) {
            throw new LevyAuthorityUnavailableException("the levy authority could not be reached", e);
        }
    }

    @Override
    public void topUp(String accountId, String idempotencyKey, long amountNgwee, String reference) {
        try {
            client.post().uri("/v1/levy-accounts/{id}/top-ups", accountId).header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("amountNgwee", amountNgwee, "reference", reference))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new LevyAuthorityUnavailableException("the levy authority did not take the top-up", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<LevyAccount> account(String accountId) {
        Map<String, Object> a;
        try {
            a = client.get().uri("/v1/levy-accounts/{id}", accountId).retrieve().body(Map.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) || e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                return Optional.empty();
            }
            throw new LevyAuthorityUnavailableException("the levy authority refused the account lookup", e);
        } catch (RestClientException e) {
            throw new LevyAuthorityUnavailableException("the levy authority could not be reached", e);
        }
        if (a == null) {
            return Optional.empty();
        }
        Object paidTo = a.get("paidTo");
        return Optional.of(new LevyAccount(a.get("id").toString(), ((Number) a.get("dailyLevyNgwee")).longValue(),
                ((Number) a.get("balanceNgwee")).longValue(), ((Number) a.get("arrearsNgwee")).longValue(),
                paidTo == null ? null : LocalDate.parse(paidTo.toString())));
    }
}
