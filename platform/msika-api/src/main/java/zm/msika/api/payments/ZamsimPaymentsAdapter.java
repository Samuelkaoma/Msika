package zm.msika.api.payments;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsUnavailableException;
import zm.msika.ledger.Money;

/**
 * Talks to the simulated Zamtel Money over HTTP. It knows zamsim's public HTTP shape and nothing of its code
 * (AGENTS.md rule 6): the request and reply are plain maps. Swapping in the real Zamtel Money means writing
 * another class like this one, not changing the port.
 */
public final class ZamsimPaymentsAdapter implements PaymentsPort {

    private final RestClient client;

    public ZamsimPaymentsAdapter(RestClient client) {
        this.client = client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String cashIn(String idempotencyKey, String agentWalletId, String customerWalletId, Money amount, String reference) {
        Map<String, Object> reply;
        try {
            reply = client.post()
                    .uri("/v1/cash-in")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "agentWalletId", agentWalletId,
                            "customerWalletId", customerWalletId,
                            "amountNgwee", amount.minorUnits(),
                            "reference", reference))
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new PaymentsUnavailableException("the payments system could not be reached or refused the request", e);
        }
        Object id = reply == null ? null : reply.get("id");
        if (id == null) {
            throw new PaymentsUnavailableException("the payments system answered without a transaction id", null);
        }
        return id.toString();
    }
}
