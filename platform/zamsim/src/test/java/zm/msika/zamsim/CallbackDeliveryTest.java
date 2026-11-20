package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import zm.msika.zamsim.callback.CallbackSigner;
import zm.msika.zamsim.callback.CallbackWorker;
import zm.msika.zamsim.fault.FaultService;
import zm.msika.zamsim.fault.FaultSettings;
import zm.msika.zamsim.wallet.WalletService;
import zm.msika.zamsim.wallet.Wallets.Transaction;
import zm.msika.zamsim.wallet.Wallets.Wallet;
import zm.msika.zamsim.wallet.Wallets.WalletType;

/**
 * Signed callbacks, retries, and each fault mode, against a real receiver that can be told to answer 500,
 * drop the connection or accept. The worker is switched off and driven by hand with {@code runOnce()}, so
 * every test is exact and none waits for a timer.
 */
class CallbackDeliveryTest extends ZamsimIntegrationTest {

    private static final String SECRET = "test-callback-secret-not-a-real-one";

    // ------------------------------------------------------------ the receiver

    record Received(String body, Map<String, String> headers) {}

    /** A tiny HTTP server standing in for Msika's callback endpoint. */
    static final class Receiver {
        final HttpServer server;
        final List<Received> received = new CopyOnWriteArrayList<>();
        /** What to answer, in order: a status code, or -1 to drop the connection. Empty means 200. */
        final Deque<Integer> answers = new ConcurrentLinkedDeque<>();

        Receiver() {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/callback", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            headers.put(":method", exchange.getRequestMethod());
            received.add(new Received(body, headers));
            Integer answer = answers.poll();
            int status = answer == null ? 200 : answer;
            if (status < 0) {
                exchange.close(); // no response at all: the sender sees a broken connection
                return;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        int port() {
            return server.getAddress().getPort();
        }
    }

    static final Receiver RECEIVER = new Receiver();

    @DynamicPropertySource
    static void callbacks(DynamicPropertyRegistry registry) {
        registry.add("zamsim.callback.url", () -> "http://localhost:" + RECEIVER.port() + "/callback");
        registry.add("zamsim.callback.secret", () -> SECRET);
        registry.add("zamsim.callback.worker.enabled", () -> "false");
        registry.add("zamsim.request-expiry.enabled", () -> "false");
        registry.add("zamsim.callback.backoff-ms", () -> "0");
        registry.add("zamsim.callback.max-attempts", () -> "4");
    }

    // --------------------------------------------------------------------- setup

    @Autowired
    WalletService wallets;

    @Autowired
    CallbackWorker worker;

    @Autowired
    FaultService faults;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    @LocalServerPort
    int port;

    private final CallbackSigner signer = new CallbackSigner(SECRET);

    @BeforeEach
    void reset() {
        jdbc.sql("delete from callback_delivery").update();
        faults.update(FaultSettings.none());
        RECEIVER.received.clear();
        RECEIVER.answers.clear();
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private Wallet funded(WalletType type, long ngwee) {
        Wallet w = wallets.createWallet(type, "Callback " + UUID.randomUUID(), null);
        if (ngwee > 0) {
            wallets.fund(key(), w.id(), ngwee, "seed");
        }
        return w;
    }

    private Transaction aTransfer() {
        Wallet a = funded(WalletType.TRADER, 10_000);
        Wallet b = funded(WalletType.TRADER, 0);
        return wallets.transfer(key(), a.id(), b.id(), 1_000, "callback test");
    }

    private long balance(UUID id) {
        return jdbc.sql("select balance_ngwee from wallet where id = :id").param("id", id).query(Long.class).single();
    }

    private List<String> deliveryStatuses() {
        return jdbc.sql("select status from callback_delivery order by id").query(String.class).list();
    }

    // ----------------------------------------------------------------- signature

    @Test
    @DisplayName("a payment is reported by a signed callback, and the signature verifies with the shared secret")
    void signedCallback() {
        Transaction tx = aTransfer();

        int attempts = worker.runOnce();

        assertThat(attempts).isEqualTo(1);
        assertThat(RECEIVER.received).hasSize(1);
        Received r = RECEIVER.received.get(0);
        assertThat(r.headers().get(":method")).isEqualTo("POST");
        assertThat(r.headers()).containsKeys("x-signature", "x-timestamp", "x-callback-id", "x-attempt");
        assertThat(r.headers().get("x-attempt")).isEqualTo("1");
        assertThat(r.headers().get("x-signature")).startsWith("sha256=").hasSize("sha256=".length() + 64);

        long timestamp = Long.parseLong(r.headers().get("x-timestamp"));
        assertThat(Math.abs(System.currentTimeMillis() / 1000 - timestamp)).as("a fresh timestamp").isLessThan(10);
        assertThat(signer.verify(timestamp, r.body(), r.headers().get("x-signature"))).isTrue();
        assertThat(r.body()).contains("\"id\":\"" + tx.id() + "\"").contains("\"status\":\"SUCCEEDED\"")
                .contains("\"kind\":\"TRANSFER\"").contains("\"amountNgwee\":1000")
                .contains("\"reference\":\"callback test\"").contains(r.headers().get("x-callback-id"));
        assertThat(deliveryStatuses()).containsExactly("DELIVERED");
    }

    @Test
    @DisplayName("the signature fails if the body, the timestamp or the secret is wrong")
    void signatureDetectsTampering() {
        aTransfer();
        worker.runOnce();
        Received r = RECEIVER.received.get(0);
        long timestamp = Long.parseLong(r.headers().get("x-timestamp"));
        String signature = r.headers().get("x-signature");

        assertThat(signer.verify(timestamp, r.body(), signature)).isTrue();
        assertThat(signer.verify(timestamp, r.body().replace("1000", "9000"), signature)).as("body changed").isFalse();
        assertThat(signer.verify(timestamp + 1, r.body(), signature)).as("timestamp changed").isFalse();
        assertThat(new CallbackSigner("some other secret").verify(timestamp, r.body(), signature)).as("wrong secret")
                .isFalse();
        assertThat(signer.verify(timestamp, r.body(), null)).isFalse();
        assertThat(signer.verify(timestamp, r.body(), "sha256=" + "0".repeat(64))).isFalse();
    }

    @Test
    @DisplayName("a secret is required to sign anything")
    void signerNeedsASecret() {
        assertThatThrownBy(() -> new CallbackSigner(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackSigner(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------- retries

    @Test
    @DisplayName("a receiver that answers 500 twice gets the same callback again until it accepts it")
    void retryAfterServerErrors() {
        RECEIVER.answers.addAll(List.of(500, 500, 200));
        aTransfer();

        int first = worker.runOnce();
        assertThat(deliveryStatuses()).as("after one 500 it is still waiting").containsExactly("PENDING");
        worker.runOnce();
        worker.runOnce();
        int nothingLeft = worker.runOnce();

        assertThat(first).isEqualTo(1);
        assertThat(nothingLeft).as("once delivered it is not sent again").isZero();
        assertThat(RECEIVER.received).hasSize(3);
        assertThat(RECEIVER.received).extracting(r -> r.headers().get("x-callback-id")).containsOnly(
                RECEIVER.received.get(0).headers().get("x-callback-id"));
        assertThat(RECEIVER.received).extracting(Received::body).containsOnly(RECEIVER.received.get(0).body());
        assertThat(RECEIVER.received).extracting(r -> r.headers().get("x-attempt")).containsExactly("1", "2", "3");
        assertThat(deliveryStatuses()).containsExactly("DELIVERED");
        for (Received r : RECEIVER.received) {
            assertThat(signer.verify(Long.parseLong(r.headers().get("x-timestamp")), r.body(), r.headers().get("x-signature")))
                    .as("every retry is signed afresh").isTrue();
        }
    }

    @Test
    @DisplayName("a dropped connection is a failure too, and its reason is kept")
    void droppedConnectionIsRetried() {
        RECEIVER.answers.addAll(List.of(-1, 200));
        aTransfer();

        worker.runOnce();
        var afterFirst = jdbc.sql("select status, attempts, last_error from callback_delivery")
                .query((rs, row) -> rs.getString("status") + "|" + rs.getInt("attempts") + "|" + rs.getString("last_error"))
                .single();
        worker.runOnce();

        assertThat(afterFirst).startsWith("PENDING|1|").doesNotEndWith("|null");
        assertThat(deliveryStatuses()).containsExactly("DELIVERED");
        assertThat(RECEIVER.received).hasSize(2);
    }

    @Test
    @DisplayName("after the last allowed attempt a callback is marked DEAD and is not sent again")
    void givesUpAfterTheMaximumAttempts() {
        for (int i = 0; i < 10; i++) {
            RECEIVER.answers.add(500);
        }
        aTransfer();

        for (int i = 0; i < 4; i++) {
            worker.runOnce();
        }
        int afterDead = worker.runOnce();

        assertThat(deliveryStatuses()).containsExactly("DEAD");
        assertThat(afterDead).isZero();
        assertThat(RECEIVER.received).as("exactly the 4 allowed attempts").hasSize(4);
        assertThat(jdbc.sql("select last_http_status from callback_delivery").query(Integer.class).single()).isEqualTo(500);
    }

    @Test
    @DisplayName("a callback that is not due yet is not sent, and a failed one waits for its backoff")
    void notSentBeforeItIsDue() {
        faults.update(new FaultSettings(0, 60_000, 0, 0, 2000));
        aTransfer();

        assertThat(worker.runOnce()).as("due in a minute").isZero();
        assertThat(RECEIVER.received).isEmpty();

        jdbc.sql("update callback_delivery set next_attempt_at = now() - interval '1 second'").update();
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(deliveryStatuses()).containsExactly("DELIVERED");
    }

    // -------------------------------------------------------------- fault modes

    @Test
    @DisplayName("fault: callbackDelayMs holds a callback back by that long")
    void faultDelay() {
        faults.update(new FaultSettings(0, 30_000, 0, 0, 2000));
        aTransfer();

        OffsetDateTime due = jdbc.sql("select next_attempt_at from callback_delivery")
                .query(OffsetDateTime.class).single();

        assertThat(due).isAfter(OffsetDateTime.now().plusSeconds(25)).isBefore(OffsetDateTime.now().plusSeconds(35));
    }

    @Test
    @DisplayName("fault: duplicateCallbackRate 1 delivers every callback twice, with the same id and the same body")
    void faultDuplicate() {
        faults.update(new FaultSettings(0, 0, 1, 0, 2000));
        aTransfer();

        int attempts = worker.runOnce();

        assertThat(attempts).isEqualTo(2);
        assertThat(RECEIVER.received).hasSize(2);
        assertThat(RECEIVER.received.get(0).headers().get("x-callback-id"))
                .isEqualTo(RECEIVER.received.get(1).headers().get("x-callback-id"));
        assertThat(RECEIVER.received.get(0).body()).isEqualTo(RECEIVER.received.get(1).body());
        assertThat(deliveryStatuses()).containsExactly("DELIVERED", "DELIVERED");
    }

    @Test
    @DisplayName("fault: reorderRate holds one callback back so a later payment's callback arrives first")
    void faultOutOfOrder() {
        faults.update(new FaultSettings(0, 0, 0, 1, 10_000));
        Transaction first = aTransfer();
        faults.update(new FaultSettings(0, 0, 0, 0, 10_000));
        Transaction second = aTransfer();

        worker.runOnce();
        assertThat(RECEIVER.received).as("only the second was due").hasSize(1);
        assertThat(RECEIVER.received.get(0).body()).contains(second.id().toString());

        jdbc.sql("update callback_delivery set next_attempt_at = now() - interval '1 second' where status = 'PENDING'").update();
        worker.runOnce();

        assertThat(RECEIVER.received).hasSize(2);
        assertThat(RECEIVER.received.get(1).body()).as("the first payment's callback came second").contains(first.id().toString());
    }

    @Test
    @DisplayName("fault: failureRate 1 fails every cash-in and transfer without moving money, and the callback says so")
    void faultFailureRate() {
        Wallet a = funded(WalletType.TRADER, 10_000);
        Wallet b = funded(WalletType.TRADER, 0);
        jdbc.sql("delete from callback_delivery").update();
        faults.update(new FaultSettings(1, 0, 0, 0, 2000));

        Transaction tx = wallets.transfer(key(), a.id(), b.id(), 1_000, null);
        Wallet agent = funded(WalletType.AGENT, 5_000);
        Transaction cashIn = wallets.cashIn(key(), agent.id(), b.id(), 500, null);

        assertThat(tx.status().name()).isEqualTo("FAILED");
        assertThat(tx.failureReason()).isEqualTo("SIMULATED_FAILURE");
        assertThat(cashIn.failureReason()).isEqualTo("SIMULATED_FAILURE");
        assertThat(balance(a.id())).as("nothing moved").isEqualTo(10_000);
        assertThat(balance(b.id())).isZero();
        assertThat(jdbc.sql("select count(*) from ledger_line where wallet_id = :w").param("w", b.id())
                .query(Long.class).single()).isZero();
        worker.runOnce();
        assertThat(RECEIVER.received).hasSize(2);
        assertThat(RECEIVER.received.get(0).body()).contains("\"status\":\"FAILED\"").contains("\"failureReason\":\"SIMULATED_FAILURE\"");
    }

    @Test
    @DisplayName("fault: failureRate does not touch admin fund and withdraw, which set up and clean up demos")
    void adminMovesAreNeverInjected() {
        faults.update(new FaultSettings(1, 0, 0, 0, 2000));
        Wallet w = wallets.createWallet(WalletType.TRADER, "Admin target", null);

        Transaction fund = wallets.fund(key(), w.id(), 700, null);
        Transaction withdraw = wallets.withdraw(key(), w.id(), 200, null);

        assertThat(fund.status().name()).isEqualTo("SUCCEEDED");
        assertThat(withdraw.status().name()).isEqualTo("SUCCEEDED");
        assertThat(balance(w.id())).isEqualTo(500);
    }

    @Test
    @DisplayName("fund and withdraw are not reported by callback, only what customers and merchants do")
    void noCallbackForAdminMoves() {
        Wallet w = wallets.createWallet(WalletType.TRADER, "Quiet", null);

        wallets.fund(key(), w.id(), 700, null);
        wallets.withdraw(key(), w.id(), 100, null);

        assertThat(jdbc.sql("select count(*) from callback_delivery").query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------ request-to-pay callbacks

    private Transaction openRequest() {
        Wallet merchant = funded(WalletType.MERCHANT, 0);
        Wallet customer = funded(WalletType.TRADER, 10_000);
        jdbc.sql("delete from callback_delivery").update();
        return wallets.requestToPay(key(), merchant.id(), customer.id(), 2_500, "SALE-9", null);
    }

    @Test
    @DisplayName("a request-to-pay is reported only when it is answered: nothing while it waits, then one callback")
    void requestToPayCallbackOnApproval() {
        Transaction request = openRequest();

        assertThat(worker.runOnce()).as("a pending request has nothing to report yet").isZero();
        wallets.approve(request.id());
        worker.runOnce();

        assertThat(RECEIVER.received).hasSize(1);
        assertThat(RECEIVER.received.get(0).body()).contains("\"id\":\"" + request.id() + "\"")
                .contains("\"kind\":\"REQUEST_TO_PAY\"").contains("\"status\":\"SUCCEEDED\"");
    }

    @Test
    @DisplayName("a rejected request-to-pay is reported as FAILED with reason REJECTED")
    void requestToPayCallbackOnRejection() {
        Transaction request = openRequest();

        wallets.reject(request.id());
        wallets.reject(request.id()); // harmless repeat: no second callback
        worker.runOnce();

        assertThat(RECEIVER.received).hasSize(1);
        assertThat(RECEIVER.received.get(0).body()).contains("\"status\":\"FAILED\"").contains("\"failureReason\":\"REJECTED\"");
    }

    @Test
    @DisplayName("an expired request-to-pay is reported as FAILED with reason EXPIRED, once")
    void requestToPayCallbackOnExpiry() {
        Transaction request = openRequest();
        jdbc.sql("update transaction set expires_at = now() - interval '1 second' where id = :id").param("id", request.id()).update();

        wallets.expireOverdue();
        wallets.expireOverdue();
        worker.runOnce();

        assertThat(RECEIVER.received).hasSize(1);
        assertThat(RECEIVER.received.get(0).body()).contains("\"failureReason\":\"EXPIRED\"");
    }

    @Test
    @DisplayName("fault: failureRate also applies when a request-to-pay is approved")
    void faultFailureRateOnApproval() {
        Transaction request = openRequest();
        faults.update(new FaultSettings(1, 0, 0, 0, 2000));

        Transaction result = wallets.approve(request.id());

        assertThat(result.status().name()).isEqualTo("FAILED");
        assertThat(result.failureReason()).isEqualTo("SIMULATED_FAILURE");
        assertThat(balance(request.fromWalletId())).as("the customer was not charged").isEqualTo(10_000);
        assertThat(wallets.approve(request.id()).failureReason()).as("approving again returns the same failure")
                .isEqualTo("SIMULATED_FAILURE");
    }

    // ------------------------------------------------------------------- outbox

    @Test
    @DisplayName("a payment that is rolled back leaves no callback behind (the callback is written with the payment)")
    void callbackIsAtomicWithThePayment() {
        Wallet a = funded(WalletType.TRADER, 10_000);
        Wallet b = funded(WalletType.TRADER, 0);
        jdbc.sql("delete from callback_delivery").update();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            wallets.transfer(key(), a.id(), b.id(), 1_000, null);
            throw new IllegalStateException("something went wrong after the payment");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(balance(a.id())).as("the payment was undone").isEqualTo(10_000);
        assertThat(jdbc.sql("select count(*) from callback_delivery").query(Long.class).single())
                .as("and so was its callback").isZero();
    }

    @Test
    @DisplayName("a repeated Idempotency-Key does not queue a second callback")
    void repeatedKeyQueuesOneCallback() {
        Wallet a = funded(WalletType.TRADER, 10_000);
        Wallet b = funded(WalletType.TRADER, 0);
        jdbc.sql("delete from callback_delivery").update();
        String k = key();

        wallets.transfer(k, a.id(), b.id(), 1_000, null);
        wallets.transfer(k, a.id(), b.id(), 1_000, null);

        assertThat(jdbc.sql("select count(*) from callback_delivery").query(Long.class).single()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------- API

    private <T> T call(HttpMethod method, String path, Object body, Class<T> type) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            if (type == Integer.class) {
                return type.cast(response.getStatusCode().value());
            }
            return response.bodyTo(type);
        });
    }

    @Test
    @DisplayName("the fault settings can be read and changed over HTTP, and bad values are refused")
    void faultsApi() {
        FaultSettings defaults = call(HttpMethod.GET, "/v1/admin/faults", null, FaultSettings.class);
        assertThat(defaults).isEqualTo(FaultSettings.none());

        FaultSettings chosen = new FaultSettings(0.25, 1500, 0.5, 0.75, 4000);
        assertThat(call(HttpMethod.PUT, "/v1/admin/faults", chosen, FaultSettings.class)).isEqualTo(chosen);
        assertThat(call(HttpMethod.GET, "/v1/admin/faults", null, FaultSettings.class)).isEqualTo(chosen);
        assertThat(faults.settings()).as("and the service sees it at once").isEqualTo(chosen);

        record Raw(double failureRate, int callbackDelayMs, double duplicateCallbackRate, double reorderRate,
                int reorderExtraDelayMs) {}
        assertThat(call(HttpMethod.PUT, "/v1/admin/faults", new Raw(1.5, 0, 0, 0, 0), Integer.class)).isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/v1/admin/faults", new Raw(-0.1, 0, 0, 0, 0), Integer.class)).isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/v1/admin/faults", new Raw(0, -1, 0, 0, 0), Integer.class)).isEqualTo(400);
        assertThat(faults.settings()).as("a refused change changed nothing").isEqualTo(chosen);
    }

    @Test
    @DisplayName("the console can list the callbacks that were queued, sent or given up on")
    void callbackListing() {
        aTransfer();

        record DeliveryBody(long id, String status, int attempts) {}
        DeliveryBody[] pending = call(HttpMethod.GET, "/v1/admin/callbacks?status=PENDING", null, DeliveryBody[].class);
        worker.runOnce();
        DeliveryBody[] delivered = call(HttpMethod.GET, "/v1/admin/callbacks?status=DELIVERED", null, DeliveryBody[].class);

        assertThat(pending).hasSize(1);
        assertThat(pending[0].attempts()).isZero();
        assertThat(delivered).hasSize(1);
        assertThat(delivered[0].attempts()).isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/v1/admin/callbacks?status=BOGUS", null, Integer.class)).isEqualTo(400);
        assertThat(new ArrayList<>(List.of(pending)).size()).isEqualTo(1);
    }
}
