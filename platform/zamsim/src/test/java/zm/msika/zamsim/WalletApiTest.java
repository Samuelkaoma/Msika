package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** The simulated Zamtel Money API through real HTTP and a real Postgres. */
class WalletApiTest extends ZamsimIntegrationTest {

    record WalletBody(UUID id, String type, String name, String msisdn, long balanceNgwee) {}

    record TxBody(UUID id, String kind, String status, UUID fromWalletId, UUID toWalletId, long amountNgwee,
            String reference, String failureReason) {}

    record LineBody(long lineId, UUID transactionId, String kind, long amountNgwee, long balanceAfterNgwee,
            UUID counterpartyWalletId, String reference) {}

    record ErrorBody(String error, String message) {}

    record Result<T>(int status, T body) {}

    record CreateWallet(String type, String name, String msisdn) {}

    record Transfer(UUID fromWalletId, UUID toWalletId, long amountNgwee, String reference) {}

    record CashIn(UUID agentWalletId, UUID customerWalletId, long amountNgwee, String reference) {}

    record AdminMove(UUID walletId, long amountNgwee, String reference) {}

    @LocalServerPort
    int port;

    private <T> Result<T> send(HttpMethod method, String path, String key, Object body, Class<T> type) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> new Result<>(response.getStatusCode().value(),
                type == Void.class ? null : response.bodyTo(type)));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private WalletBody wallet(String type, String name) {
        Result<WalletBody> r = send(HttpMethod.POST, "/v1/wallets", null, new CreateWallet(type, name, null), WalletBody.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private TxBody fund(WalletBody wallet, long ngwee) {
        Result<TxBody> r = send(HttpMethod.POST, "/v1/admin/fund", key(), new AdminMove(wallet.id(), ngwee, "seed"), TxBody.class);
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().status()).isEqualTo("SUCCEEDED");
        return r.body();
    }

    private long balance(WalletBody w) {
        return send(HttpMethod.GET, "/v1/wallets/" + w.id(), null, null, WalletBody.class).body().balanceNgwee();
    }

    // ------------------------------------------------------------------ wallets

    @Test
    @DisplayName("a wallet is created empty, can be read back, and its msisdn is unique")
    void createAndReadWallets() {
        Result<WalletBody> created = send(HttpMethod.POST, "/v1/wallets", null,
                new CreateWallet("TRADER", "Mary Banda", "+260977000101"), WalletBody.class);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().balanceNgwee()).isZero();
        assertThat(created.body().type()).isEqualTo("TRADER");
        assertThat(send(HttpMethod.GET, "/v1/wallets/" + created.body().id(), null, null, WalletBody.class).body().msisdn())
                .isEqualTo("+260977000101");
        assertThat(send(HttpMethod.POST, "/v1/wallets", null, new CreateWallet("AGENT", "Other", "+260977000101"),
                ErrorBody.class).status()).as("same msisdn").isEqualTo(409);
    }

    @Test
    @DisplayName("bad wallet requests are 400, an unknown wallet is 404, and there is only one issuer")
    void walletValidation() {
        assertThat(send(HttpMethod.POST, "/v1/wallets", null, new CreateWallet("ISSUER", "Another issuer", null),
                ErrorBody.class).status()).as("cannot create an issuer").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/wallets", null, new CreateWallet("TRADER", " ", null), ErrorBody.class)
                .status()).isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/wallets", null, new CreateWallet("TRADER", "Mary", "0977000101"),
                ErrorBody.class).status()).as("msisdn must be E.164").isEqualTo(400);
        assertThat(send(HttpMethod.GET, "/v1/wallets/" + UUID.randomUUID(), null, null, ErrorBody.class).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the console lists wallets with balances and recent transactions, newest first, never the issuer")
    void consoleLists() {
        WalletBody agent = wallet("AGENT", "Listed Agent");
        WalletBody trader = wallet("TRADER", "Listed Trader");
        fund(agent, 10_000);
        TxBody cashIn = send(HttpMethod.POST, "/v1/cash-in", key(), new CashIn(agent.id(), trader.id(), 4_000, "list"),
                TxBody.class).body();

        List<WalletBody> wallets = List.of(send(HttpMethod.GET, "/v1/wallets?limit=1000", null, null, WalletBody[].class).body());
        List<TxBody> txs = List.of(send(HttpMethod.GET, "/v1/transactions?limit=5", null, null, TxBody[].class).body());

        assertThat(wallets).extracting(WalletBody::id).contains(agent.id(), trader.id());
        assertThat(wallets).noneMatch(w -> w.type().equals("ISSUER"));
        assertThat(wallets.stream().filter(w -> w.id().equals(trader.id())).findFirst().orElseThrow().balanceNgwee())
                .isEqualTo(4_000);
        assertThat(txs.get(0)).isEqualTo(cashIn);
        assertThat(send(HttpMethod.GET, "/v1/transactions?limit=0", null, null, ErrorBody.class).status()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- movements

    @Test
    @DisplayName("an agent's cash-in moves money from the agent's float to the customer, with two balancing ledger lines")
    void cashIn() {
        WalletBody agent = wallet("AGENT", "Mwila");
        WalletBody trader = wallet("TRADER", "Mary");
        fund(agent, 100_000);

        Result<TxBody> result = send(HttpMethod.POST, "/v1/cash-in", key(),
                new CashIn(agent.id(), trader.id(), 45_000, "DEP-8F2K"), TxBody.class);

        assertThat(result.status()).as("accepted").isEqualTo(202);
        assertThat(result.body().status()).isEqualTo("SUCCEEDED");
        assertThat(result.body().kind()).isEqualTo("CASH_IN");
        assertThat(balance(agent)).isEqualTo(55_000);
        assertThat(balance(trader)).isEqualTo(45_000);
        List<LineBody> lines = List.of(send(HttpMethod.GET, "/v1/statements?wallet=" + trader.id(), null, null,
                LineBody[].class).body());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).extracting(LineBody::amountNgwee, LineBody::balanceAfterNgwee, LineBody::counterpartyWalletId,
                LineBody::reference).containsExactly(45_000L, 45_000L, agent.id(), "DEP-8F2K");
        assertThat(send(HttpMethod.GET, "/v1/transactions/" + result.body().id(), null, null, TxBody.class).body())
                .isEqualTo(result.body());
    }

    @Test
    @DisplayName("cash-in must come from an agent and go to a customer; transfers cannot touch the issuer")
    void wrongWalletTypes() {
        WalletBody agent = wallet("AGENT", "Mwila");
        WalletBody otherAgent = wallet("AGENT", "Chola");
        WalletBody trader = wallet("TRADER", "Mary");
        fund(agent, 10_000);
        fund(trader, 10_000);

        assertThat(send(HttpMethod.POST, "/v1/cash-in", key(), new CashIn(trader.id(), agent.id(), 100, null),
                ErrorBody.class).status()).as("from a trader").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/cash-in", key(), new CashIn(agent.id(), otherAgent.id(), 100, null),
                ErrorBody.class).status()).as("to an agent").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/transfers", key(),
                new Transfer(UUID.fromString("00000000-0000-0000-0000-000000000001"), trader.id(), 100, null),
                ErrorBody.class).status()).as("from the issuer").isEqualTo(400);
        assertThat(balance(agent)).isEqualTo(10_000);
        assertThat(balance(trader)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a transfer without enough money fails cleanly: recorded as FAILED, nothing moves, no ledger lines")
    void insufficientFunds() {
        WalletBody poor = wallet("TRADER", "Poor");
        WalletBody rich = wallet("TRADER", "Rich");
        fund(poor, 1_000);

        Result<TxBody> result = send(HttpMethod.POST, "/v1/transfers", key(),
                new Transfer(poor.id(), rich.id(), 1_001, "too much"), TxBody.class);

        assertThat(result.status()).as("the request was understood, so not an HTTP error").isEqualTo(202);
        assertThat(result.body().status()).isEqualTo("FAILED");
        assertThat(result.body().failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(poor)).isEqualTo(1_000);
        assertThat(balance(rich)).isZero();
        assertThat(send(HttpMethod.GET, "/v1/statements?wallet=" + rich.id(), null, null, LineBody[].class).body()).isEmpty();
        assertThat(send(HttpMethod.GET, "/v1/transactions/" + result.body().id(), null, null, TxBody.class).body().status())
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("spending exactly the whole balance works, and leaves exactly zero")
    void exactBalance() {
        WalletBody a = wallet("TRADER", "A");
        WalletBody b = wallet("TRADER", "B");
        fund(a, 5_000);

        assertThat(send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), b.id(), 5_000, null), TxBody.class)
                .body().status()).isEqualTo("SUCCEEDED");
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("invalid requests: no key, zero or negative amount, same wallet, unknown wallet")
    void invalidRequests() {
        WalletBody a = wallet("TRADER", "A");
        WalletBody b = wallet("TRADER", "B");
        fund(a, 5_000);

        assertThat(send(HttpMethod.POST, "/v1/transfers", null, new Transfer(a.id(), b.id(), 100, null), ErrorBody.class)
                .status()).as("no Idempotency-Key").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), b.id(), 0, null), ErrorBody.class)
                .status()).as("zero").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), b.id(), -5, null), ErrorBody.class)
                .status()).as("negative").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), a.id(), 100, null), ErrorBody.class)
                .status()).as("same wallet").isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), UUID.randomUUID(), 100, null),
                ErrorBody.class).status()).as("unknown wallet").isEqualTo(404);
        assertThat(balance(a)).as("nothing moved").isEqualTo(5_000);
    }

    // ------------------------------------------------------------- idempotency

    @Test
    @DisplayName("repeating a request with the same Idempotency-Key returns the first result and moves the money once")
    void repeatedKeyReturnsTheFirstResult() {
        WalletBody a = wallet("TRADER", "A");
        WalletBody b = wallet("TRADER", "B");
        fund(a, 10_000);
        String k = key();
        Transfer request = new Transfer(a.id(), b.id(), 3_000, "once");

        Result<TxBody> first = send(HttpMethod.POST, "/v1/transfers", k, request, TxBody.class);
        Result<TxBody> second = send(HttpMethod.POST, "/v1/transfers", k, request, TxBody.class);
        Result<TxBody> third = send(HttpMethod.POST, "/v1/transfers", k, request, TxBody.class);

        assertThat(second.body()).isEqualTo(first.body());
        assertThat(third.body().id()).isEqualTo(first.body().id());
        assertThat(balance(a)).isEqualTo(7_000);
        assertThat(balance(b)).isEqualTo(3_000);
        assertThat(send(HttpMethod.GET, "/v1/statements?wallet=" + b.id(), null, null, LineBody[].class).body())
                .as("one ledger line, not three").hasSize(1);
    }

    @Test
    @DisplayName("a FAILED result is repeated too, even if the wallet could afford it by then")
    void failedResultIsRepeated() {
        WalletBody a = wallet("TRADER", "A");
        WalletBody b = wallet("TRADER", "B");
        String k = key();
        Transfer request = new Transfer(a.id(), b.id(), 2_000, null);

        TxBody failed = send(HttpMethod.POST, "/v1/transfers", k, request, TxBody.class).body();
        fund(a, 50_000);
        TxBody again = send(HttpMethod.POST, "/v1/transfers", k, request, TxBody.class).body();

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(again).as("the same answer as the first time").isEqualTo(failed);
        assertThat(balance(b)).isZero();
    }

    @Test
    @DisplayName("the same key for a different request is a conflict, and moves nothing")
    void sameKeyDifferentRequest() {
        WalletBody a = wallet("TRADER", "A");
        WalletBody b = wallet("TRADER", "B");
        fund(a, 10_000);
        String k = key();
        send(HttpMethod.POST, "/v1/transfers", k, new Transfer(a.id(), b.id(), 1_000, null), TxBody.class);

        Result<ErrorBody> conflict = send(HttpMethod.POST, "/v1/transfers", k, new Transfer(a.id(), b.id(), 1_001, null),
                ErrorBody.class);

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().message()).contains(k);
        assertThat(balance(b)).isEqualTo(1_000);
    }

    // ---------------------------------------------------------------- statements

    @Test
    @DisplayName("a statement lists a wallet's lines oldest first with a running balance, and can be limited by date")
    void statements() {
        WalletBody a = wallet("TRADER", "A");
        WalletBody b = wallet("TRADER", "B");
        fund(a, 10_000);
        send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), b.id(), 1_000, "one"), TxBody.class);
        send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(b.id(), a.id(), 400, "two"), TxBody.class);
        send(HttpMethod.POST, "/v1/transfers", key(), new Transfer(a.id(), b.id(), 100, "three"), TxBody.class);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lusaka"));

        List<LineBody> all = List.of(send(HttpMethod.GET, "/v1/statements?wallet=" + a.id(), null, null, LineBody[].class).body());
        List<LineBody> forToday = List.of(send(HttpMethod.GET,
                "/v1/statements?wallet=" + a.id() + "&from=" + today + "&to=" + today, null, null, LineBody[].class).body());
        List<LineBody> tomorrow = List.of(send(HttpMethod.GET,
                "/v1/statements?wallet=" + a.id() + "&from=" + today.plusDays(1), null, null, LineBody[].class).body());
        List<LineBody> limited = List.of(send(HttpMethod.GET, "/v1/statements?wallet=" + a.id() + "&limit=2", null, null,
                LineBody[].class).body());

        assertThat(all).extracting(LineBody::amountNgwee).containsExactly(10_000L, -1_000L, 400L, -100L);
        assertThat(all).extracting(LineBody::balanceAfterNgwee).containsExactly(10_000L, 9_000L, 9_400L, 9_300L);
        assertThat(all.get(all.size() - 1).balanceAfterNgwee()).isEqualTo(balance(a));
        assertThat(forToday).hasSize(4);
        assertThat(tomorrow).isEmpty();
        assertThat(limited).hasSize(2);
        assertThat(send(HttpMethod.GET, "/v1/statements?wallet=" + UUID.randomUUID(), null, null, ErrorBody.class).status())
                .isEqualTo(404);
        assertThat(send(HttpMethod.GET, "/v1/statements?wallet=" + a.id() + "&limit=0", null, null, ErrorBody.class).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("money can leave the system through the issuer too, and cannot be withdrawn beyond the balance")
    void withdraw() {
        WalletBody a = wallet("AGENT", "A");
        fund(a, 5_000);

        TxBody ok = send(HttpMethod.POST, "/v1/admin/withdraw", key(), new AdminMove(a.id(), 2_000, "cash out"), TxBody.class).body();
        TxBody tooMuch = send(HttpMethod.POST, "/v1/admin/withdraw", key(), new AdminMove(a.id(), 9_000, null), TxBody.class).body();

        assertThat(ok.status()).isEqualTo("SUCCEEDED");
        assertThat(tooMuch.status()).isEqualTo("FAILED");
        assertThat(balance(a)).isEqualTo(3_000);
    }
}
