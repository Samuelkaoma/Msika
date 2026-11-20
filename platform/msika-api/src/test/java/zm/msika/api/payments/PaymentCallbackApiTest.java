package zm.msika.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiTest;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.PaymentReference;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.ledger.Currency;
import zm.msika.ledger.Money;

/**
 * The callback endpoint as the payments system uses it: real HTTP, real signatures, a real database.
 * Everything a hostile or unreliable sender can do is tried here.
 */
class PaymentCallbackApiTest extends ApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    PaymentRequestRepository requests;

    @Autowired
    CallbackVerifier verifier;

    private PaymentTestSupport.Stall stall;
    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        stall = PaymentTestSupport.newStall(jdbc, tenant);
        request = newRequest(45_000);
    }

    private PaymentRequest newRequest(long ngwee) {
        PaymentRequest r = new PaymentRequest(UUID.randomUUID(), stall.councilId(), PaymentRequest.Purpose.DEPOSIT,
                stall.stallId(), Money.ofMinor(ngwee, Currency.ZMW), PaymentRequest.Status.SENT, null, null,
                Instant.now(), null);
        tenant.inCouncil(stall.councilId(), () -> requests.add(r));
        return r;
    }

    private static String body(String callbackId, String txn, String status, long amount, String reference, String reason) {
        return "{\"callbackId\":\"" + callbackId + "\",\"event\":\"transaction.completed\",\"transaction\":{"
                + "\"id\":\"" + txn + "\",\"kind\":\"CASH_IN\",\"status\":\"" + status + "\","
                + "\"amountNgwee\":" + amount + ",\"reference\":" + (reference == null ? "null" : "\"" + reference + "\"")
                + ",\"failureReason\":" + (reason == null ? "null" : "\"" + reason + "\"")
                + ",\"completedAt\":\"" + Instant.now() + "\"}}";
    }

    private String succeeded(String txn, long amount) {
        return body("cb-" + txn, txn, "SUCCEEDED", amount, request.reference(), null);
    }

    private record Reply(int status, String body) {}

    private Reply post(String json, String timestamp, String signature) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/payments/callback"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (timestamp != null) {
                b.header("X-Timestamp", timestamp);
            }
            if (signature != null) {
                b.header("X-Signature", signature);
            }
            HttpResponse<String> response = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends a correctly signed callback, as the payments system would. */
    private Reply signed(String json) {
        long now = Instant.now().getEpochSecond();
        return post(json, Long.toString(now), verifier.sign(now, json.getBytes(StandardCharsets.UTF_8)));
    }

    private long ledgerEntries() {
        return ownerJdbc().sql("select count(*) from ledger_entry where council_id = :c")
                .param("c", stall.councilId()).query(Long.class).single();
    }

    private PaymentRequest current() {
        return tenant.inCouncil(stall.councilId(), () -> requests.find(stall.councilId(), request.id()).orElseThrow());
    }

    @Test
    @DisplayName("a signed SUCCEEDED callback records one DEPOSITED entry and marks the request SUCCEEDED")
    void recorded() {
        Reply reply = signed(succeeded("tx-ok", 45_000));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("RECORDED");
        assertThat(ledgerEntries()).isEqualTo(1);
        var row = ownerJdbc().sql("select kind, provenance, amount_ngwee, idempotency_key from ledger_entry where council_id = :c")
                .param("c", stall.councilId()).query().singleRow();
        assertThat(row).containsEntry("kind", "DEPOSIT").containsEntry("provenance", "DEPOSITED")
                .containsEntry("amount_ngwee", 45_000L).containsEntry("idempotency_key", "zamsim:tx-ok");
        assertThat(current().status()).isEqualTo(PaymentRequest.Status.SUCCEEDED);
        assertThat(current().paymentsTransactionId()).isEqualTo("tx-ok");
    }

    @Test
    @DisplayName("the same callback sent again, in a row, records nothing more")
    void duplicateInARow() {
        String json = succeeded("tx-dup", 45_000);

        assertThat(signed(json).body()).contains("RECORDED");
        for (int i = 0; i < 4; i++) {
            Reply again = signed(json);
            assertThat(again.status()).isEqualTo(200);
            assertThat(again.body()).contains("DUPLICATE");
        }
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same callback sent 16 times at the same moment records exactly one entry")
    void duplicateAtTheSameTime() throws Exception {
        String json = succeeded("tx-race", 45_000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Reply>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> signed(json));
            }
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : pool.invokeAll(calls)) {
                replies.add(f.get());
            }
            assertThat(replies).extracting(Reply::status).containsOnly(200);
            assertThat(replies.stream().filter(r -> r.body().contains("RECORDED"))).hasSize(1);
            assertThat(replies.stream().filter(r -> r.body().contains("DUPLICATE"))).hasSize(15);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("a callback that is not signed, or signed wrongly, is refused with 401 and changes nothing")
    void badSignature() {
        String json = succeeded("tx-bad", 45_000);
        long now = Instant.now().getEpochSecond();
        String good = verifier.sign(now, json.getBytes(StandardCharsets.UTF_8));

        assertThat(post(json, null, null).status()).as("no headers").isEqualTo(401);
        assertThat(post(json, Long.toString(now), null).status()).as("no signature").isEqualTo(401);
        assertThat(post(json, null, good).status()).as("no timestamp").isEqualTo(401);
        assertThat(post(json, Long.toString(now), "sha256=" + "0".repeat(64)).status()).as("wrong signature").isEqualTo(401);
        assertThat(post(json, Long.toString(now), good.substring("sha256=".length())).status()).as("no prefix").isEqualTo(401);
        assertThat(post(json, "not-a-number", good).status()).as("junk timestamp").isEqualTo(401);
        assertThat(ledgerEntries()).isZero();
        assertThat(current().status()).isEqualTo(PaymentRequest.Status.SENT);
    }

    @Test
    @DisplayName("a body changed after it was signed is refused, even by one digit of the amount")
    void tamperedBody() {
        String json = succeeded("tx-tamper", 45_000);
        long now = Instant.now().getEpochSecond();
        String signature = verifier.sign(now, json.getBytes(StandardCharsets.UTF_8));
        String tampered = json.replace("45000", "95000");

        assertThat(tampered).isNotEqualTo(json);
        assertThat(post(tampered, Long.toString(now), signature).status()).isEqualTo(401);
        assertThat(ledgerEntries()).isZero();
    }

    @Test
    @DisplayName("a correctly signed but old (or future) callback is refused: a captured callback cannot be replayed later")
    void replayWindow() {
        String json = succeeded("tx-old", 45_000);
        long now = Instant.now().getEpochSecond();
        long tooOld = now - 6 * 60;
        long tooNew = now + 6 * 60;
        long fresh = now - 4 * 60;

        assertThat(post(json, Long.toString(tooOld), verifier.sign(tooOld, json.getBytes(StandardCharsets.UTF_8))).status())
                .as("6 minutes old").isEqualTo(401);
        assertThat(post(json, Long.toString(tooNew), verifier.sign(tooNew, json.getBytes(StandardCharsets.UTF_8))).status())
                .as("6 minutes in the future").isEqualTo(401);
        assertThat(ledgerEntries()).isZero();
        assertThat(post(json, Long.toString(fresh), verifier.sign(fresh, json.getBytes(StandardCharsets.UTF_8))).status())
                .as("4 minutes old is still fine").isEqualTo(200);
        assertThat(ledgerEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("a signed body that is not a callback Msika understands is 400, not a crash")
    void malformed() {
        assertThat(signed("not json at all").status()).isEqualTo(400);
        assertThat(signed("{}").status()).isEqualTo(400);
        assertThat(signed(body("cb", "tx-1", "PENDING", 45_000, request.reference(), null)).status())
                .as("a status that is not final").isEqualTo(400);
        assertThat(signed(body("cb", "tx-1", "SUCCEEDED", 45_000, request.reference(), null)
                .replace("\"completedAt\":\"", "\"completedAt\":\"yesterday")).status()).isEqualTo(400);
        assertThat(ledgerEntries()).isZero();
    }

    @Test
    @DisplayName("a callback for a payment that is not one of ours is accepted (200) and ignored, so it is not retried forever")
    void unmatched() {
        Reply none = signed(body("cb-1", "tx-x", "SUCCEEDED", 45_000, null, null));
        Reply foreign = signed(body("cb-2", "tx-y", "SUCCEEDED", 45_000, "SOMEONE-ELSE", null));
        Reply unknown = signed(body("cb-3", "tx-z", "SUCCEEDED", 45_000,
                PaymentReference.of(stall.councilId(), UUID.randomUUID()), null));
        Reply otherCouncil = signed(body("cb-4", "tx-w", "SUCCEEDED", 45_000,
                PaymentReference.of(UUID.randomUUID(), request.id()), null));

        for (Reply r : List.of(none, foreign, unknown, otherCouncil)) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body()).contains("UNMATCHED");
        }
        assertThat(ledgerEntries()).isZero();
        assertThat(current().status()).isEqualTo(PaymentRequest.Status.SENT);
    }

    @Test
    @DisplayName("a FAILED callback is noted on the request and puts nothing on the ledger")
    void failed() {
        Reply reply = signed(body("cb-f", "tx-fail", "FAILED", 45_000, request.reference(), "SIMULATED_FAILURE"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("FAILURE_NOTED");
        assertThat(current().status()).isEqualTo(PaymentRequest.Status.FAILED);
        assertThat(current().failureReason()).isEqualTo("SIMULATED_FAILURE");
        assertThat(ledgerEntries()).isZero();
    }

    @Test
    @DisplayName("a confirmed amount that was not the amount asked for is a 500 (so it is retried and looked at), and nothing is recorded")
    void amountMismatch() {
        Reply reply = signed(succeeded("tx-wrong", 45_001));

        assertThat(reply.status()).isEqualTo(500);
        assertThat(ledgerEntries()).isZero();
        assertThat(current().status()).isEqualTo(PaymentRequest.Status.SENT);
    }

    @Test
    @DisplayName("callbacks for two councils stay apart: each lands on its own council's ledger")
    void councilsStayApart() {
        PaymentTestSupport.Stall other = PaymentTestSupport.newStall(jdbc, tenant);
        PaymentRequest otherRequest = new PaymentRequest(UUID.randomUUID(), other.councilId(), PaymentRequest.Purpose.DEPOSIT,
                other.stallId(), Money.ofMinor(1_000, Currency.ZMW), PaymentRequest.Status.SENT, null, null, Instant.now(), null);
        tenant.inCouncil(other.councilId(), () -> requests.add(otherRequest));

        signed(succeeded("tx-a", 45_000));
        signed(body("cb-b", "tx-b", "SUCCEEDED", 1_000, otherRequest.reference(), null));

        assertThat(ledgerEntries()).isEqualTo(1);
        assertThat(ownerJdbc().sql("select amount_ngwee from ledger_entry where council_id = :c")
                .param("c", other.councilId()).query(Long.class).list()).containsExactly(1_000L);
        // And a callback's own council decides where it is recorded: a request is not found in another council.
        assertThat(tenant.inCouncil(other.councilId(), () -> requests.find(other.councilId(), request.id()))).isEmpty();
    }

    @Test
    @DisplayName("the payment_request table is row-level secured: with no council set, no rows are visible to the app")
    void rowLevelSecurity() {
        Long visible = tenant.inCouncil(UUID.randomUUID(), () ->
                jdbc.sql("select count(*) from payment_request").query(Long.class).single());

        assertThat(visible).isZero();
    }
}
