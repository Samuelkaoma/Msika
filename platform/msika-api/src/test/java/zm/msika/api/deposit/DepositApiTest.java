package zm.msika.api.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsUnavailableException;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.ledger.Money;

/** The deposit lifecycle (card M-15) through the real HTTP API, with the payments system played by a script. */
class DepositApiTest extends ApiTest {

    /** Stands in for the payments system: records every call, and can be told to be unreachable. */
    static final class ScriptedPayments implements PaymentsPort {
        @Override
        public String transfer(String key, String from, String to, Money amount, String reference) {
            throw new UnsupportedOperationException("no transfers in this test");
        }

        record Call(String key, String agentWallet, String customerWallet, long amount, String reference) {}

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger unreachable = new AtomicInteger();
        /** The wallets the payments system knows, by id. */
        final Map<String, PaymentsWallet> wallets = new ConcurrentHashMap<>();
        volatile boolean walletLookupDown;

        @Override
        public String cashIn(String key, String agentWallet, String customerWallet, Money amount, String reference) {
            calls.add(new Call(key, agentWallet, customerWallet, amount.minorUnits(), reference));
            if (unreachable.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new PaymentsUnavailableException("scripted outage");
            }
            return "sim-" + key;
        }

        @Override
        public Optional<PaymentsWallet> findWallet(String walletId) {
            if (walletLookupDown) {
                throw new PaymentsUnavailableException("scripted outage");
            }
            return Optional.ofNullable(wallets.get(walletId));
        }

        @Override
        public List<zm.msika.core.payments.StatementLine> statement(String walletId, java.time.LocalDate from,
                java.time.LocalDate to) {
            return List.of();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedPayments scriptedPayments() {
            return new ScriptedPayments();
        }
    }

    record Wallet(String walletId, UUID councilId, String role, UUID stallId, UUID agentId) {}

    record DepositBody(UUID id, UUID stallId, UUID agentId, Long amountNgwee, String status, String reference,
            UUID paymentRequestId, Long ledgerEntryId, String statusReason) {}

    record AgentBody(UUID id) {}

    record ErrorBody(String error, String message) {}

    record StartRequest(UUID agentId, UUID stallId, long amountNgwee) {}

    record DisputeRequest(String reason) {}

    record RegisterAgentRequest(String phone, String displayName) {}

    record RegisterWalletRequest(String walletId, String role, UUID stallId, UUID agentId) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ScriptedPayments payments;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    CallbackVerifier verifier;

    private Fixture council;
    private UUID stallId;
    private UUID agentId;
    private String agentPhone;
    private String traderPhone;
    private String traderWallet;
    private String agentWallet;
    private String merchantWallet;
    private String supplierWallet;
    private String tag;
    private Instant completedAt;

    @BeforeEach
    void setUp() {
        payments.calls.clear();
        payments.unreachable.set(0);
        payments.walletLookupDown = false;
        council = newCouncil();
        UUID market = UUID.randomUUID();
        stallId = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'DEP', 'Deposit Market', '20:00', now())")
                    .param("id", market).param("c", council.councilId()).update();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'DEP-A-1', 'STANDARD', 'ALLOCATED', :token)")
                    .param("id", stallId).param("c", council.councilId()).param("m", market)
                    .param("token", UUID.randomUUID().toString().replace("-", "")).update();
        });
        String digits = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        agentPhone = "+260977" + digits;
        traderPhone = "+260966" + digits;
        agentId = api().post(council.adminToken(), base() + "/agents",
                new RegisterAgentRequest(agentPhone, "Test Agent"), AgentBody.class).body().id();
        // The stall's holder, so its trader wallet can be checked against the holder's phone.
        UUID traderId = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> {
            jdbc.sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :phone, 'Test Trader')")
                    .param("id", traderId).param("c", council.councilId()).param("phone", traderPhone).update();
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) "
                            + "values (:id, :c, :s, :t, now())")
                    .param("id", UUID.randomUUID()).param("c", council.councilId()).param("s", stallId)
                    .param("t", traderId).update();
        });
        tag = UUID.randomUUID().toString().substring(0, 8);
        completedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        traderWallet = "w-trader-" + tag;
        agentWallet = "w-agent-" + tag;
        merchantWallet = "w-merchant-" + tag;
        supplierWallet = "w-supplier-" + tag;
        scripted(traderWallet, PaymentsWallet.Kind.TRADER, traderPhone);
        scripted(merchantWallet, PaymentsWallet.Kind.MERCHANT, null);
        scripted(agentWallet, PaymentsWallet.Kind.AGENT, agentPhone);
        scripted(supplierWallet, PaymentsWallet.Kind.SUPPLIER, null);
        registerWallet(traderWallet, "TRADER", stallId, null);
        registerWallet(merchantWallet, "MERCHANT", stallId, null);
        registerWallet(agentWallet, "AGENT", null, agentId);
        registerWallet(supplierWallet, "SUPPLIER", null, null);
    }

    private void scripted(String id, PaymentsWallet.Kind kind, String msisdn) {
        payments.wallets.put(id, new PaymentsWallet(id, kind, msisdn));
    }

    private String base() {
        return "/api/v1/councils/" + council.councilId();
    }

    private void registerWallet(String id, String role, UUID stall, UUID agent) {
        Result<Wallet> r = api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest(id, role, stall, agent), Wallet.class);
        assertThat(r.status()).as("register " + id).isEqualTo(201);
    }

    private Result<DepositBody> start(String key, long ngwee) {
        return send(HttpMethod.POST, base() + "/deposits", key, new StartRequest(agentId, stallId, ngwee), DepositBody.class);
    }

    private <T> Result<T> send(HttpMethod method, String path, String key, Object body, Class<T> type) {
        return api().send(council.adminToken(), method, path, body, MediaType.APPLICATION_JSON, type, key);
    }

    private DepositBody deposit(UUID id) {
        return api().get(council.adminToken(), base() + "/deposits/" + id, DepositBody.class).body();
    }

    private record Reply(int status, String body) {}

    /** A signed callback, as the payments system sends it. */
    private Reply callback(String txn, String status, long amount, String reference, String kind, String from, String to) {
        txn = tag + "-" + txn;
        String json = "{\"callbackId\":\"cb-" + txn + "\",\"event\":\"transaction.completed\",\"transaction\":{"
                + "\"id\":\"" + txn + "\",\"kind\":\"" + kind + "\",\"status\":\"" + status + "\",\"amountNgwee\":" + amount
                + ",\"fromWalletId\":\"" + from + "\",\"toWalletId\":\"" + to + "\","
                + "\"reference\":" + (reference == null ? "null" : "\"" + reference + "\"") + ","
                + "\"failureReason\":" + ("FAILED".equals(status) ? "\"INSUFFICIENT_FUNDS\"" : "null") + ","
                + "\"completedAt\":\"" + completedAt + "\"}}";
        return signedCallback(json);
    }

    /** Sends a body exactly as given, signed as the payments system signs it. */
    private Reply signedCallback(String json) {
        long now = Instant.now().getEpochSecond();
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/payments/callback"))
                    .header("Content-Type", "application/json")
                    .header("X-Timestamp", Long.toString(now))
                    .header("X-Signature", verifier.sign(now, json.getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private Reply depositCallback(DepositBody d, String txn, String status) {
        return callback(txn, status, d.amountNgwee(), PaymentRefs.of(council.councilId(), d.paymentRequestId()),
                "CASH_IN", agentWallet, traderWallet);
    }

    private List<String> ledger(String where) {
        return ownerJdbc().sql("select kind || ':' || provenance || ':' || amount_ngwee from ledger_entry where council_id = :c "
                        + where + " order by id")
                .param("c", council.councilId()).query(String.class).list();
    }

    @Test
    @DisplayName("start: 202 PENDING, one call to the payments system with the right wallets, key and reference; nothing on the ledger yet")
    void start() {
        Result<DepositBody> r = start("k-1", 45_000);

        assertThat(r.status()).isEqualTo(202);
        DepositBody d = r.body();
        assertThat(d.status()).isEqualTo("PENDING");
        assertThat(d.amountNgwee()).isEqualTo(45_000);
        assertThat(d.reference()).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}");
        assertThat(payments.calls).hasSize(1);
        ScriptedPayments.Call call = payments.calls.get(0);
        assertThat(call.agentWallet()).isEqualTo(agentWallet);
        assertThat(call.customerWallet()).isEqualTo(traderWallet);
        assertThat(call.amount()).isEqualTo(45_000);
        assertThat(call.key()).isEqualTo("msika-" + d.paymentRequestId());
        assertThat(call.reference()).isEqualTo(PaymentRefs.of(council.councilId(), d.paymentRequestId()));
        assertThat(ledger("")).isEmpty();
    }

    @Test
    @DisplayName("the same Idempotency-Key again: 200, the same deposit, and the payments system is NOT asked again")
    void idempotentStart() {
        DepositBody first = start("k-1", 45_000).body();

        Result<DepositBody> again = start("k-1", 45_000);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().id()).isEqualTo(first.id());
        assertThat(payments.calls).hasSize(1);
        assertThat(api().get(council.adminToken(), base() + "/deposits", DepositBody[].class).body()).hasSize(1);
    }

    @Test
    @DisplayName("PENDING to CONFIRMED when the signed callback says it succeeded: one DEPOSITED entry, linked")
    void confirmed() {
        DepositBody d = start("k-1", 45_000).body();

        Reply reply = depositCallback(d, "tx-1", "SUCCEEDED");

        assertThat(reply.status()).isEqualTo(200);
        DepositBody after = deposit(d.id());
        assertThat(after.status()).isEqualTo("CONFIRMED");
        assertThat(ledger("")).containsExactly("DEPOSIT:DEPOSITED:45000");
        assertThat(after.ledgerEntryId()).isNotNull();
        assertThat(depositCallback(d, "tx-1", "SUCCEEDED").body()).contains("DUPLICATE");
        assertThat(ledger("")).hasSize(1);
    }

    @Test
    @DisplayName("PENDING to FAILED when the callback says it failed: the reason is kept, nothing on the ledger")
    void failed() {
        DepositBody d = start("k-1", 45_000).body();

        depositCallback(d, "tx-1", "FAILED");

        assertThat(deposit(d.id()).status()).isEqualTo("FAILED");
        assertThat(deposit(d.id()).statusReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(ledger("")).isEmpty();
    }

    @Test
    @DisplayName("PENDING to CANCELLED; a late SUCCEEDED callback is recorded (the money moved) and flags the deposit DISPUTED")
    void paidAfterCancel() {
        DepositBody d = start("k-1", 45_000).body();
        Result<DepositBody> cancelled = send(HttpMethod.POST, base() + "/deposits/" + d.id() + "/cancel", null, null, DepositBody.class);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().status()).isEqualTo("CANCELLED");

        Reply reply = depositCallback(d, "tx-late", "SUCCEEDED");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(ledger("")).containsExactly("DEPOSIT:DEPOSITED:45000");
        DepositBody after = deposit(d.id());
        assertThat(after.status()).isEqualTo("DISPUTED");
        assertThat(after.statusReason()).isEqualTo("PAID_AFTER_CANCEL");
        assertThat(after.ledgerEntryId()).isNotNull();
    }

    @Test
    @DisplayName("a late FAILED callback after the cancel leaves it CANCELLED with nothing on the ledger")
    void failedAfterCancel() {
        DepositBody d = start("k-1", 45_000).body();
        send(HttpMethod.POST, base() + "/deposits/" + d.id() + "/cancel", null, null, DepositBody.class);

        depositCallback(d, "tx-1", "FAILED");

        assertThat(deposit(d.id()).status()).isEqualTo("CANCELLED");
        assertThat(ledger("")).isEmpty();
    }

    @Test
    @DisplayName("a cancel and the confirmation arriving at the same moment: one wins, the ledger has exactly one entry, never CANCELLED with money")
    void cancelRacesConfirmation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                DepositBody d = start("race-" + round, 1_000 + round).body();
                int n = round;
                Future<Integer> cancel = pool.submit(() -> send(HttpMethod.POST,
                        base() + "/deposits/" + d.id() + "/cancel", null, null, ErrorBody.class).status());
                Future<Reply> confirm = pool.submit(() -> depositCallback(d, "tx-race-" + n, "SUCCEEDED"));

                int cancelStatus = cancel.get();
                assertThat(confirm.get().status()).isEqualTo(200);
                DepositBody after = deposit(d.id());
                assertThat(after.status()).as("round " + round).isIn("CONFIRMED", "DISPUTED");
                assertThat(after.ledgerEntryId()).isNotNull();
                assertThat(cancelStatus).as("cancel lost the race exactly when the deposit was confirmed")
                        .isEqualTo(after.status().equals("CONFIRMED") ? 409 : 200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger("")).hasSize(25);
    }

    @Test
    @DisplayName("dispute: only a CONFIRMED deposit; the reason is kept and the ledger is untouched")
    void dispute() {
        DepositBody d = start("k-1", 45_000).body();
        assertThat(send(HttpMethod.POST, base() + "/deposits/" + d.id() + "/dispute", null, new DisputeRequest("wrong amount"),
                ErrorBody.class).status()).as("pending cannot be disputed").isEqualTo(409);
        depositCallback(d, "tx-1", "SUCCEEDED");

        Result<DepositBody> r = send(HttpMethod.POST, base() + "/deposits/" + d.id() + "/dispute", null,
                new DisputeRequest("I gave 400, not 450"), DepositBody.class);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().status()).isEqualTo("DISPUTED");
        assertThat(r.body().statusReason()).isEqualTo("I gave 400, not 450");
        assertThat(ledger("")).hasSize(1);
        assertThat(send(HttpMethod.POST, base() + "/deposits/" + d.id() + "/dispute", null, null, ErrorBody.class).status())
                .as("already disputed").isEqualTo(409);
        assertThat(send(HttpMethod.POST, base() + "/deposits/" + d.id() + "/cancel", null, null, ErrorBody.class).status())
                .as("a confirmed deposit cannot be cancelled").isEqualTo(409);
    }

    @Test
    @DisplayName("payments system unreachable: 502, the deposit is saved PENDING, and the same command again retries it")
    void unreachable() {
        payments.unreachable.set(1);

        Result<ErrorBody> failed = send(HttpMethod.POST, base() + "/deposits", "k-out",
                new StartRequest(agentId, stallId, 45_000), ErrorBody.class);

        assertThat(failed.status()).isEqualTo(502);
        assertThat(failed.body().error()).isEqualTo("payments_unavailable");
        DepositBody[] saved = api().get(council.adminToken(), base() + "/deposits?status=PENDING", DepositBody[].class).body();
        assertThat(saved).hasSize(1);

        Result<DepositBody> retried = start("k-out", 45_000);

        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().id()).isEqualTo(saved[0].id());
        assertThat(payments.calls).hasSize(2);
        assertThat(payments.calls.get(1).key()).as("same payments key, so the money moves once").isEqualTo(payments.calls.get(0).key());
        assertThat(start("k-out", 45_000).status()).isEqualTo(200);
        assertThat(payments.calls).as("sent once it was accepted").hasSize(2);
    }

    @Test
    @DisplayName("bad commands are 400/404 and leave nothing behind: no key, bad amount, unknown agent or stall, no wallet, vacant stall")
    void validation() {
        assertThat(send(HttpMethod.POST, base() + "/deposits", null, new StartRequest(agentId, stallId, 100), ErrorBody.class).status())
                .as("no key").isEqualTo(400);
        assertThat(start("a", 0).status()).isEqualTo(400);
        assertThat(start("b", -1).status()).isEqualTo(400);
        assertThat(start("c", 5_000_001).status()).as("over the limit").isEqualTo(400);
        assertThat(send(HttpMethod.POST, base() + "/deposits", "d", new StartRequest(UUID.randomUUID(), stallId, 100), ErrorBody.class)
                .status()).isEqualTo(404);
        assertThat(send(HttpMethod.POST, base() + "/deposits", "e", new StartRequest(agentId, UUID.randomUUID(), 100), ErrorBody.class)
                .status()).isEqualTo(404);
        assertThat(send(HttpMethod.POST, base() + "/deposits", "f", new StartRequest(null, stallId, 100), ErrorBody.class).status())
                .isEqualTo(400);
        tenant.inCouncil(council.councilId(), () -> jdbc.sql("update stall set status = 'VACANT' where id = :s")
                .param("s", stallId).update());
        assertThat(start("g", 100).status()).as("a stall nobody holds").isEqualTo(400);
        assertThat(api().get(council.adminToken(), base() + "/deposits?status=NONSENSE", ErrorBody.class).status()).isEqualTo(400);
        assertThat(payments.calls).isEmpty();
        assertThat(api().get(council.adminToken(), base() + "/deposits", DepositBody[].class).body()).isEmpty();
    }

    @Test
    @DisplayName("a customer's payment into the stall's merchant wallet is recorded as a VERIFIED MERCHANT_SALE")
    void merchantSale() {
        Reply reply = callback("tx-sale", "SUCCEEDED", 12_000, null, "REQUEST_TO_PAY", "w-customer", merchantWallet);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("RECORDED");
        assertThat(ledger("")).containsExactly("MERCHANT_SALE:VERIFIED:12000");
        assertThat(callback("tx-sale", "SUCCEEDED", 12_000, null, "REQUEST_TO_PAY", "w-customer", merchantWallet).body())
                .contains("DUPLICATE");
        assertThat(ledger("")).hasSize(1);
    }

    @Test
    @DisplayName("a payment from the stall to a supplier wallet is a VERIFIED SUPPLIER_PAYMENT; unknown wallets and failures are ignored")
    void supplierPayment() {
        callback("tx-sup", "SUCCEEDED", 30_000, null, "TRANSFER", traderWallet, supplierWallet);
        Reply unknown = callback("tx-x", "SUCCEEDED", 500, null, "TRANSFER", "w-nobody", "w-nobody-either");
        Reply failed = callback("tx-f", "FAILED", 500, null, "REQUEST_TO_PAY", "w-customer", merchantWallet);

        assertThat(ledger("")).containsExactly("SUPPLIER_PAYMENT:VERIFIED:30000");
        assertThat(unknown.body()).contains("UNMATCHED");
        assertThat(failed.body()).contains("UNMATCHED");
    }

    @Test
    @DisplayName("another council's users cannot see or change this council's deposits and wallets")
    void councilsAreSeparate() {
        DepositBody d = start("k-1", 45_000).body();
        Fixture other = newCouncil();

        assertThat(api().get(other.adminToken(), base() + "/deposits/" + d.id(), ErrorBody.class).status()).isEqualTo(403);
        assertThat(api().get(other.adminToken(), base() + "/wallets", ErrorBody.class).status()).isEqualTo(403);
        assertThat(api().send(other.adminToken(), HttpMethod.POST, base() + "/deposits/" + d.id() + "/cancel", null,
                MediaType.APPLICATION_JSON, ErrorBody.class, null).status()).isEqualTo(403);
        assertThat(api().get(null, base() + "/deposits", ErrorBody.class).status()).as("not signed in").isEqualTo(401);
        // The database also refuses it: with another council set, no deposit and no wallet rows are visible.
        long visible = tenant.inCouncil(other.councilId(), () -> jdbc.sql(
                "select (select count(*) from deposit) + (select count(*) from payments_wallet)").query(Long.class).single());
        assertThat(visible).isZero();
    }

    @Test
    @DisplayName("registering wallets: a duplicate wallet id or role is 409, a wrong shape is 400, an unknown stall is 404")
    void walletRegistration() {
        assertThat(api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest(traderWallet, "TRADER", stallId, null), ErrorBody.class).status()).isEqualTo(409);
        assertThat(api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest("w-other", "TRADER", stallId, null), ErrorBody.class).status()).isEqualTo(409);
        assertThat(api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest("w-other", "TRADER", null, null), ErrorBody.class).status()).isEqualTo(400);
        assertThat(api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest("w-other", "BANK", null, null), ErrorBody.class).status()).isEqualTo(400);
        assertThat(api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest("w-other", "MERCHANT", UUID.randomUUID(), null), ErrorBody.class).status()).isEqualTo(404);
        assertThat(api().get(council.adminToken(), base() + "/wallets", Wallet[].class).body()).hasSize(4);
    }

    private int registerStatus(String id, String role, UUID stall, UUID agent) {
        return api().post(council.adminToken(), base() + "/wallets",
                new RegisterWalletRequest(id, role, stall, agent), ErrorBody.class).status();
    }

    private long walletRows(String id) {
        return ownerJdbc().sql("select count(*) from payments_wallet where wallet_id = :id")
                .param("id", id).query(Long.class).single();
    }

    @Test
    @DisplayName("a wallet the payments system does not know is refused with 400 and not stored")
    void unknownWalletIsRefused() {
        String id = "w-nowhere-" + tag;
        assertThat(registerStatus(id, "SUPPLIER", null, null)).isEqualTo(400);
        assertThat(walletRows(id)).isZero();
    }

    @Test
    @DisplayName("a trader wallet on someone else's phone, or of the wrong kind, is refused and not stored")
    void someoneElsesWalletIsRefused() {
        // A second stall, held by the same trader, so only the wallet decides the answer.
        UUID secondStall = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> {
            UUID market = jdbc.sql("select market_id from stall where id = :s").param("s", stallId).query(UUID.class).single();
            UUID trader = jdbc.sql("select trader_id from stall_allocation where stall_id = :s").param("s", stallId)
                    .query(UUID.class).single();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'DEP-A-2', 'STANDARD', 'ALLOCATED', :token)")
                    .param("id", secondStall).param("c", council.councilId()).param("m", market)
                    .param("token", UUID.randomUUID().toString().replace("-", "")).update();
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) "
                            + "values (:id, :c, :s, :t, now())")
                    .param("id", UUID.randomUUID()).param("c", council.councilId()).param("s", secondStall)
                    .param("t", trader).update();
        });
        String strangers = "w-stranger-" + tag;
        scripted(strangers, PaymentsWallet.Kind.TRADER, "+260955000123");
        String merchantKind = "w-merchant-kind-" + tag;
        scripted(merchantKind, PaymentsWallet.Kind.MERCHANT, traderPhone);

        assertThat(registerStatus(strangers, "TRADER", secondStall, null)).isEqualTo(400);
        assertThat(registerStatus(merchantKind, "TRADER", secondStall, null)).isEqualTo(400);
        assertThat(walletRows(strangers) + walletRows(merchantKind)).isZero();

        // An agent's wallet must be on that agent's phone too.
        String digits = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        UUID secondAgent = api().post(council.adminToken(), base() + "/agents",
                new RegisterAgentRequest("+260976" + digits, "Second Agent"), AgentBody.class).body().id();
        String agentsWallet = "w-agent-2-" + tag;
        scripted(agentsWallet, PaymentsWallet.Kind.AGENT, agentPhone);
        assertThat(registerStatus(agentsWallet, "AGENT", null, secondAgent)).as("the first agent's phone").isEqualTo(400);
        scripted(agentsWallet, PaymentsWallet.Kind.AGENT, "+260976" + digits);
        assertThat(registerStatus(agentsWallet, "AGENT", null, secondAgent)).as("now on the right phone").isEqualTo(201);
    }

    @Test
    @DisplayName("if the payments system cannot be asked about a wallet, the answer is 502 and nothing is stored")
    void walletLookupOutage() {
        String id = "w-later-" + tag;
        scripted(id, PaymentsWallet.Kind.SUPPLIER, null);
        payments.walletLookupDown = true;

        assertThat(registerStatus(id, "SUPPLIER", null, null)).isEqualTo(502);
        assertThat(walletRows(id)).isZero();
    }

    @Test
    @DisplayName("a callback with a missing, zero, negative or fractional amount is refused with 400, so it is not retried")
    void callbackWithABadAmount() {
        String good = "{\"callbackId\":\"cb-%s\",\"transaction\":{\"id\":\"%s\",\"kind\":\"REQUEST_TO_PAY\","
                + "\"status\":\"SUCCEEDED\",%s\"fromWalletId\":\"w-customer\",\"toWalletId\":\"" + merchantWallet + "\","
                + "\"reference\":null,\"completedAt\":\"" + completedAt + "\"}}";
        String[] amounts = {"", "\"amountNgwee\":0,", "\"amountNgwee\":-500,", "\"amountNgwee\":12.5,",
                "\"amountNgwee\":\"12000\","};
        for (int i = 0; i < amounts.length; i++) {
            String txn = tag + "-bad-" + i;
            Reply reply = signedCallback(String.format(good, txn, txn, amounts[i]));
            assertThat(reply.status()).as("amount field: [" + amounts[i] + "]").isEqualTo(400);
        }
        assertThat(ledger("")).isEmpty();
    }

    @Test
    @DisplayName("a sale whose free-text reference happens to look like an Msika reference is still recorded as a sale")
    void lookAlikeReferenceIsStillASale() {
        String lookAlike = PaymentRefs.of(council.councilId(), UUID.randomUUID());

        Reply reply = callback("tx-lookalike", "SUCCEEDED", 7_000, lookAlike, "REQUEST_TO_PAY", "w-customer", merchantWallet);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("RECORDED");
        assertThat(ledger("")).containsExactly("MERCHANT_SALE:VERIFIED:7000");
    }

    /** Builds the reference the payments system echoes back; the same format as {@code PaymentReference}. */
    private static final class PaymentRefs {
        static String of(UUID councilId, UUID requestId) {
            return zm.msika.core.payments.PaymentReference.of(councilId, requestId);
        }
    }
}
