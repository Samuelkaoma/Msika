package zm.msika.api.payments;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsWallet;
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

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PaymentsWallet> findWallet(String walletId) {
        Map<String, Object> reply;
        try {
            reply = client.get()
                    .uri("/v1/wallets/{id}", walletId)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            // zamsim answers 404 for an unknown id and 400 for one that is not even shaped like an id.
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) || e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                return Optional.empty();
            }
            throw new PaymentsUnavailableException("the payments system refused the wallet lookup", e);
        } catch (RestClientException e) {
            throw new PaymentsUnavailableException("the payments system could not be reached", e);
        }
        if (reply == null || reply.get("id") == null || reply.get("type") == null) {
            throw new PaymentsUnavailableException("the payments system answered a wallet lookup without an id or type", null);
        }
        Object msisdn = reply.get("msisdn");
        return Optional.of(new PaymentsWallet(reply.get("id").toString(), kind(reply.get("type").toString()),
                msisdn == null ? null : msisdn.toString()));
    }

    /** zamsim's wallet types, mapped to the kinds Msika knows. Its COUNCIL, LENDER and ISSUER wallets are OTHER. */
    private static PaymentsWallet.Kind kind(String zamsimType) {
        return switch (zamsimType) {
            case "TRADER" -> PaymentsWallet.Kind.TRADER;
            case "MERCHANT" -> PaymentsWallet.Kind.MERCHANT;
            case "AGENT" -> PaymentsWallet.Kind.AGENT;
            case "SUPPLIER" -> PaymentsWallet.Kind.SUPPLIER;
            default -> PaymentsWallet.Kind.OTHER;
        };
    }
}
