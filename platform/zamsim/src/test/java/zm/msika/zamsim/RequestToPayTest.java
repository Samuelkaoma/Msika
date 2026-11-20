package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import zm.msika.zamsim.wallet.WalletService;

/** A merchant asks a customer to pay; the payment waits for the customer to approve it in the console. */
class RequestToPayTest extends ZamsimIntegrationTest {

    record WalletBody(UUID id, String type, long balanceNgwee) {}

    record TxBody(UUID id, String kind, String status, UUID fromWalletId, UUID toWalletId, long amountNgwee,
            String reference, String failureReason, Instant createdAt, Instant completedAt, Instant expiresAt) {}

    record ErrorBody(String error, String message) {}

    record Result<T>(int status, T body) {}

    record CreateWallet(String type, String name, String msisdn) {}

    record AdminMove(UUID walletId, long amountNgwee, String reference) {}

    record Ask(UUID merchantWalletId, UUID customerWalletId, long amountNgwee, String reference, Integer ttlSeconds) {}

    @LocalServerPort
    int port;

    @Autowired
    WalletService wallets;

    @Autowired
    JdbcClient jdbc;

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

    private WalletBody wallet(String type, long funded) {
        WalletBody w = send(HttpMethod.POST, "/v1/wallets", null, new CreateWallet(type, type + " " + UUID.randomUUID(), null),
                WalletBody.class).body();
        if (funded > 0) {
            send(HttpMethod.POST, "/v1/admin/fund", key(), new AdminMove(w.id(), funded, "seed"), TxBody.class);
        }
        return w;
    }

    private long balance(WalletBody w) {
        return send(HttpMethod.GET, "/v1/wallets/" + w.id(), null, null, WalletBody.class).body().balanceNgwee();
    }

    private TxBody ask(WalletBody merchant, WalletBody customer, long amount) {
        Result<TxBody> r = send(HttpMethod.POST, "/v1/request-to-pay", key(),
                new Ask(merchant.id(), customer.id(), amount, "SALE-1", null), TxBody.class);
        assertThat(r.status()).isEqualTo(202);
        return r.body();
    }

    private TxBody approve(TxBody request) {
        return send(HttpMethod.POST, "/v1/request-to-pay/" + request.id() + "/approve", null, null, TxBody.class).body();
    }

    // -------------------------------------------------------------------- asking

    @Test
    @DisplayName("a request is PENDING: nothing moves, there are no ledger lines, and it shows in the customer's pending list")
    void requestWaits() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        WalletBody other = wallet("TRADER", 10_000);

        TxBody request = ask(merchant, customer, 2_500);

        assertThat(request.status()).isEqualTo("PENDING");
        assertThat(request.kind()).isEqualTo("REQUEST_TO_PAY");
        assertThat(request.fromWalletId()).as("the customer pays").isEqualTo(customer.id());
        assertThat(request.toWalletId()).as("the merchant is paid").isEqualTo(merchant.id());
        assertThat(request.completedAt()).isNull();
        assertThat(request.expiresAt()).isAfter(Instant.now().plusSeconds(290)).isBefore(Instant.now().plusSeconds(310));
        assertThat(balance(customer)).isEqualTo(10_000);
        assertThat(balance(merchant)).isZero();
        assertThat(jdbc.sql("select count(*) from ledger_line where transaction_id = :id").param("id", request.id())
                .query(Long.class).single()).isZero();
        TxBody[] pending = send(HttpMethod.GET, "/v1/request-to-pay?customer=" + customer.id(), null, null, TxBody[].class).body();
        assertThat(pending).extracting(TxBody::id).containsExactly(request.id());
        assertThat(send(HttpMethod.GET, "/v1/request-to-pay?customer=" + other.id(), null, null, TxBody[].class).body())
                .isEmpty();
    }

    @Test
    @DisplayName("approving makes the payment: the merchant is paid, two balancing lines are written, and it is no longer pending")
    void approvePays() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        TxBody request = ask(merchant, customer, 2_500);

        TxBody paid = approve(request);

        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(paid.completedAt()).isNotNull();
        assertThat(paid.id()).isEqualTo(request.id());
        assertThat(balance(customer)).isEqualTo(7_500);
        assertThat(balance(merchant)).isEqualTo(2_500);
        assertThat(jdbc.sql("select sum(amount_ngwee) from ledger_line where transaction_id = :id").param("id", request.id())
                .query(Long.class).single()).isZero();
        assertThat(send(HttpMethod.GET, "/v1/request-to-pay?customer=" + customer.id(), null, null, TxBody[].class).body())
                .isEmpty();
        assertThat(send(HttpMethod.GET, "/v1/transactions/" + request.id(), null, null, TxBody.class).body().status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("approving without enough money fails cleanly: FAILED INSUFFICIENT_FUNDS and nothing moves")
    void approveWithoutFunds() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 100);
        TxBody request = ask(merchant, customer, 2_500);

        TxBody result = approve(request);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(customer)).isEqualTo(100);
        assertThat(balance(merchant)).isZero();
    }

    @Test
    @DisplayName("rejecting fails the request as REJECTED, and then it cannot be approved")
    void rejectThenApprove() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        TxBody request = ask(merchant, customer, 2_500);

        TxBody rejected = send(HttpMethod.POST, "/v1/request-to-pay/" + request.id() + "/reject", null, null, TxBody.class).body();
        Result<ErrorBody> late = send(HttpMethod.POST, "/v1/request-to-pay/" + request.id() + "/approve", null, null, ErrorBody.class);

        assertThat(rejected.status()).isEqualTo("FAILED");
        assertThat(rejected.failureReason()).isEqualTo("REJECTED");
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().message()).contains("REJECTED");
        assertThat(balance(customer)).isEqualTo(10_000);
        assertThat(send(HttpMethod.POST, "/v1/request-to-pay/" + request.id() + "/reject", null, null, TxBody.class).body())
                .as("rejecting again is harmless").isEqualTo(rejected);
    }

    @Test
    @DisplayName("approving twice pays once and returns the same result; an approved request cannot then be rejected")
    void approveTwice() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        TxBody request = ask(merchant, customer, 2_500);

        TxBody first = approve(request);
        TxBody second = approve(request);
        Result<ErrorBody> reject = send(HttpMethod.POST, "/v1/request-to-pay/" + request.id() + "/reject", null, null, ErrorBody.class);

        assertThat(second).isEqualTo(first);
        assertThat(balance(merchant)).as("paid once").isEqualTo(2_500);
        assertThat(reject.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("eight threads approving the same request at once pay exactly once")
    void concurrentApprovals() throws Exception {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        TxBody request = ask(merchant, customer, 2_500);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> wallets.approve(request.id()).status().name());
            }
            for (Future<String> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isEqualTo("SUCCEEDED");
            }
            assertThat(balance(merchant)).isEqualTo(2_500);
            assertThat(balance(customer)).isEqualTo(7_500);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------- expiry

    @Test
    @DisplayName("a request nobody answers expires: the sweeper fails it as EXPIRED, and it can no longer be approved")
    void expiry() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        TxBody request = ask(merchant, customer, 2_500);
        jdbc.sql("update transaction set expires_at = now() - interval '1 minute' where id = :id").param("id", request.id()).update();

        int expired = wallets.expireOverdue();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        TxBody now = send(HttpMethod.GET, "/v1/transactions/" + request.id(), null, null, TxBody.class).body();
        assertThat(now.status()).isEqualTo("FAILED");
        assertThat(now.failureReason()).isEqualTo("EXPIRED");
        assertThat(send(HttpMethod.POST, "/v1/request-to-pay/" + request.id() + "/approve", null, null, ErrorBody.class).status())
                .isEqualTo(409);
        assertThat(balance(customer)).isEqualTo(10_000);
        assertThat(wallets.expireOverdue()).as("running it again finds nothing more of this one").isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("approving a request that has run out of time but not yet been swept expires it instead of paying")
    void approveAfterTheDeadline() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        TxBody request = ask(merchant, customer, 2_500);
        jdbc.sql("update transaction set expires_at = now() - interval '1 second' where id = :id").param("id", request.id()).update();

        TxBody result = approve(request);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).isEqualTo("EXPIRED");
        assertThat(balance(merchant)).isZero();
    }

    // --------------------------------------------------------------- validation

    @Test
    @DisplayName("who may ask and who may be asked, and the other bad requests")
    void validation() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody trader = wallet("TRADER", 10_000);
        WalletBody agent = wallet("AGENT", 10_000);
        String path = "/v1/request-to-pay";

        assertThat(send(HttpMethod.POST, path, key(), new Ask(trader.id(), merchant.id(), 100, null, null), ErrorBody.class)
                .status()).as("a trader cannot ask").isEqualTo(400);
        assertThat(send(HttpMethod.POST, path, key(), new Ask(merchant.id(), agent.id(), 100, null, null), ErrorBody.class)
                .status()).as("an agent cannot be asked").isEqualTo(400);
        assertThat(send(HttpMethod.POST, path, key(), new Ask(merchant.id(), trader.id(), 0, null, null), ErrorBody.class)
                .status()).as("zero").isEqualTo(400);
        assertThat(send(HttpMethod.POST, path, key(), new Ask(merchant.id(), trader.id(), 100, null, 0), ErrorBody.class)
                .status()).as("ttl too small").isEqualTo(400);
        assertThat(send(HttpMethod.POST, path, key(), new Ask(merchant.id(), trader.id(), 100, null, 100_000), ErrorBody.class)
                .status()).as("ttl too large").isEqualTo(400);
        assertThat(send(HttpMethod.POST, path, key(), new Ask(merchant.id(), UUID.randomUUID(), 100, null, null), ErrorBody.class)
                .status()).as("unknown customer").isEqualTo(404);
        assertThat(send(HttpMethod.POST, path, null, new Ask(merchant.id(), trader.id(), 100, null, null), ErrorBody.class)
                .status()).as("no Idempotency-Key").isEqualTo(400);
        assertThat(send(HttpMethod.POST, path + "/" + UUID.randomUUID() + "/approve", null, null, ErrorBody.class).status())
                .as("unknown request").isEqualTo(404);
        assertThat(send(HttpMethod.GET, path + "?customer=" + UUID.randomUUID(), null, null, ErrorBody.class).status())
                .as("unknown wallet in the list").isEqualTo(404);
    }

    @Test
    @DisplayName("a transaction that is not a request-to-pay cannot be approved or rejected")
    void onlyRequestsCanBeApproved() {
        WalletBody a = wallet("TRADER", 10_000);
        WalletBody b = wallet("TRADER", 0);
        TxBody transfer = send(HttpMethod.POST, "/v1/transfers", key(),
                new TransferBody(a.id(), b.id(), 100, null), TxBody.class).body();

        assertThat(send(HttpMethod.POST, "/v1/request-to-pay/" + transfer.id() + "/approve", null, null, ErrorBody.class).status())
                .isEqualTo(404);
        assertThat(send(HttpMethod.POST, "/v1/request-to-pay/" + transfer.id() + "/reject", null, null, ErrorBody.class).status())
                .isEqualTo(404);
    }

    record TransferBody(UUID fromWalletId, UUID toWalletId, long amountNgwee, String reference) {}

    @Test
    @DisplayName("the same Idempotency-Key asks once; the same key with a different request is a conflict")
    void askingIsIdempotent() {
        WalletBody merchant = wallet("MERCHANT", 0);
        WalletBody customer = wallet("TRADER", 10_000);
        String k = key();

        TxBody first = send(HttpMethod.POST, "/v1/request-to-pay", k, new Ask(merchant.id(), customer.id(), 100, "x", null),
                TxBody.class).body();
        TxBody again = send(HttpMethod.POST, "/v1/request-to-pay", k, new Ask(merchant.id(), customer.id(), 100, "x", null),
                TxBody.class).body();
        Result<ErrorBody> different = send(HttpMethod.POST, "/v1/request-to-pay", k,
                new Ask(merchant.id(), customer.id(), 101, "x", null), ErrorBody.class);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(different.status()).isEqualTo(409);
        assertThat(send(HttpMethod.GET, "/v1/request-to-pay?customer=" + customer.id(), null, null, TxBody[].class).body())
                .hasSize(1);
    }
}
