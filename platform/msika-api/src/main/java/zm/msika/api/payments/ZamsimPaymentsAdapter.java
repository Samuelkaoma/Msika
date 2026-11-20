package zm.msika.api.payments;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import zm.msika.core.payments.StatementLine;
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
        Object balance = reply.get("balanceNgwee");
        return Optional.of(new PaymentsWallet(reply.get("id").toString(), kind(reply.get("type").toString()),
                msisdn == null ? null : msisdn.toString(), balance instanceof Number n ? n.longValue() : null));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<StatementLine> statement(String walletId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> reply;
        try {
            reply = client.get()
                    .uri(u -> u.path("/v1/statements")
                            .queryParam("wallet", walletId)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("limit", STATEMENT_LIMIT)
                            .build())
                    .retrieve()
                    .body(List.class);
        } catch (RestClientException e) {
            throw new PaymentsUnavailableException("the payments system could not give a statement", e);
        }
        if (reply == null) {
            throw new PaymentsUnavailableException("the payments system answered a statement request with nothing", null);
        }
        if (reply.size() >= STATEMENT_LIMIT) {
            // A statement cut short would make settled payments look missing. Refuse rather than guess.
            throw new PaymentsUnavailableException("the statement for wallet " + walletId + " has " + STATEMENT_LIMIT
                    + " or more lines, more than one request can return", null);
        }
        List<StatementLine> lines = new ArrayList<>(reply.size());
        for (Map<String, Object> line : reply) {
            Object counterparty = line.get("counterpartyWalletId");
            Object reference = line.get("reference");
            lines.add(new StatementLine(
                    String.valueOf(line.get("transactionId")),
                    String.valueOf(line.get("kind")),
                    ((Number) line.get("amountNgwee")).longValue(),
                    counterparty == null ? null : counterparty.toString(),
                    reference == null ? null : reference.toString(),
                    Instant.parse(line.get("at").toString())));
        }
        return lines;
    }

    /** The most lines zamsim returns for one statement request. */
    static final int STATEMENT_LIMIT = 5000;

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
