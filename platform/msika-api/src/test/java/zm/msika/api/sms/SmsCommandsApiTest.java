package zm.msika.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiTest;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.payments.StatementLine;
import zm.msika.ledger.Money;

/** Every SMS command wired to its use case (card M-19), through the webhook, the database and the callbacks. */
class SmsCommandsApiTest extends ApiTest {

    /** The payments system, played by a script: records cash-ins and answers wallet lookups with a balance. */
    static final class ScriptedPayments implements PaymentsPort {
        @Override
        public String transfer(String key, String from, String to, Money amount, String reference) {
            throw new UnsupportedOperationException("no transfers in this test");
        }

        record Call(String key, String agentWallet, String customerWallet, long amount, String reference) {}

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Long> balances = new ConcurrentHashMap<>();

        @Override
        public String cashIn(String key, String agentWallet, String customerWallet, Money amount, String reference) {
            calls.add(new Call(key, agentWallet, customerWallet, amount.minorUnits(), reference));
            return "sim-" + key;
        }

        @Override
        public Optional<PaymentsWallet> findWallet(String walletId) {
            Long balance = balances.get(walletId);
            return balance == null ? Optional.empty()
                    : Optional.of(new PaymentsWallet(walletId, PaymentsWallet.Kind.TRADER, null, balance));
        }

        @Override
        public List<StatementLine> statement(String walletId, LocalDate from, LocalDate to) {
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

    record AgentBody(UUID id) {}

    record RegisterAgentRequest(String phone, String displayName) {}

    record ApproveRequest(String displayName, String language) {}

    record RejectRequest(String reason) {}

    record RegistrationBody(UUID id, String stallCode, String phone, String status, UUID traderId) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ScriptedPayments payments;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    Clock clock;

    @Autowired
    CallbackVerifier paymentsVerifier;

    @Autowired
    @Qualifier("smsGatewayVerifier")
    CallbackVerifier smsVerifier;

    private Fixture council;
    private String marketCode;
    private UUID stallId;
    private UUID vacantStallId;
    private String stallCode;
    private String traderPhone;
    private String agentPhone;
    private UUID agentId;
    private String traderWallet;
    private String agentWallet;

    private static String phone(String prefix) {
        return prefix + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    @BeforeEach
    void setUp() {
        council = newCouncil();
        UUID market = UUID.randomUUID();
        // Five random letters: stall codes must not repeat across the test councils sharing this database, or a REG
        // from a stranger would (rightly) match more than one stall.
        StringBuilder codeLetters = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            codeLetters.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        marketCode = codeLetters.toString();
        stallId = UUID.randomUUID();
        vacantStallId = UUID.randomUUID();
        stallCode = marketCode + "-B-114";
        traderPhone = phone("+260966");
        agentPhone = phone("+260977");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        traderWallet = "w-trader-" + tag;
        agentWallet = "w-agent-" + tag;
        agentId = api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/agents",
                new RegisterAgentRequest(agentPhone, "Mwila"), AgentBody.class).body().id();
        UUID traderId = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, :code, 'Soweto Test', '20:00', now())")
                    .param("id", market).param("c", council.councilId()).param("code", marketCode).update();
            for (Object[] s : new Object[][] {{stallId, stallCode, "ALLOCATED"}, {vacantStallId, marketCode + "-B-115", "VACANT"}}) {
                jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                                + "values (:id, :c, :m, :code, 'STANDARD', :status, :token)")
                        .param("id", s[0]).param("c", council.councilId()).param("m", market).param("code", s[1])
                        .param("status", s[2]).param("token", UUID.randomUUID().toString().replace("-", "")).update();
            }
            jdbc.sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :p, 'Test Trader')")
                    .param("id", traderId).param("c", council.councilId()).param("p", traderPhone).update();
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, now())")
                    .param("id", UUID.randomUUID()).param("c", council.councilId()).param("s", stallId).param("t", traderId)
                    .update();
            jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, stall_id) values (:w, :c, 'TRADER', :s)")
                    .param("w", traderWallet).param("c", council.councilId()).param("s", stallId).update();
            jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, agent_id) values (:w, :c, 'AGENT', :a)")
                    .param("w", agentWallet).param("c", council.councilId()).param("a", agentId).update();
        });
    }

    // -------------------------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------------------------

    private void text(String from, String body) {
        String json = "{\"gatewayMessageId\":\"gw-" + UUID.randomUUID() + "\",\"from\":\"" + from + "\",\"body\":\"" + body
                + "\",\"receivedAt\":\"" + Instant.now() + "\"}";
        long now = clock.instant().getEpochSecond();
        int status = post("/api/v1/sms/inbound", json, "X-Msika-Timestamp", "X-Msika-Signature",
                smsVerifier.sign(now, json.getBytes(StandardCharsets.UTF_8)), now);
        assertThat(status).isEqualTo(200);
        waitUntil(() -> ownerJdbc().sql("select count(*) from sms_inbound where from_msisdn = :p and status = 'RECEIVED'")
                .param("p", from).query(Long.class).single() == 0);
    }

    private int post(String path, String json, String timestampHeader, String signatureHeader, String signature, long now) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .header(timestampHeader, Long.toString(now))
                    .header(signatureHeader, signature)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void callback(String txn, String status, long amount, String reference) {
        String json = "{\"callbackId\":\"cb-" + txn + "\",\"event\":\"transaction.completed\",\"transaction\":{"
                + "\"id\":\"" + txn + "\",\"kind\":\"CASH_IN\",\"status\":\"" + status + "\",\"amountNgwee\":" + amount
                + ",\"fromWalletId\":\"" + agentWallet + "\",\"toWalletId\":\"" + traderWallet + "\","
                + "\"reference\":\"" + reference + "\","
                + "\"failureReason\":" + ("FAILED".equals(status) ? "\"INSUFFICIENT_FUNDS\"" : "null") + ","
                + "\"completedAt\":\"" + Instant.now() + "\"}}";
        long now = clock.instant().getEpochSecond();
        assertThat(post("/api/v1/payments/callback", json, "X-Timestamp", "X-Signature",
                paymentsVerifier.sign(now, json.getBytes(StandardCharsets.UTF_8)), now)).isEqualTo(200);
    }

    private static void waitUntil(BooleanSupplier condition) {
        Instant end = Instant.now().plusSeconds(20);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("condition not met within 20 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** The texts queued for a phone, oldest first. */
    private List<String> outbox(String to) {
        return ownerJdbc().sql("select body from sms_outbound where to_msisdn = :p order by created_at, id")
                .param("p", to).query(String.class).list();
    }

    private List<String> templates(String to) {
        return ownerJdbc().sql("select template from sms_outbound where to_msisdn = :p order by created_at, id")
                .param("p", to).query(String.class).list();
    }

    /** Waits until the phone has n texts queued (some are queued after the commit) and returns the last. */
    private String lastTextTo(String to, int n) {
        waitUntil(() -> outbox(to).size() >= n);
        List<String> all = outbox(to);
        assertThat(all).hasSize(n);
        return all.get(n - 1);
    }

    private Map<String, Object> deposit() {
        return ownerJdbc().sql("select status, reference, amount_ngwee, ledger_entry_id, payment_request_id from deposit "
                        + "where council_id = :c order by created_at desc limit 1")
                .param("c", council.councilId()).query().singleRow();
    }

    private List<String> ledger() {
        return ownerJdbc().sql("select kind || ':' || provenance || ':' || amount_ngwee from ledger_entry where council_id = :c order by id")
                .param("c", council.councilId()).query(String.class).list();
    }

    private ScriptedPayments.Call lastCashIn() {
        waitUntil(() -> payments.calls.stream().anyMatch(c -> c.agentWallet().equals(agentWallet)));
        return payments.calls.stream().filter(c -> c.agentWallet().equals(agentWallet)).reduce((a, b) -> b).orElseThrow();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Deposits
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DEP B114 450 from an agent ends with a confirmed deposit, a DEPOSITED entry and the trader's confirmation SMS")
    void depositEndToEnd() {
        text(agentPhone, "DEP B114 450");

        ScriptedPayments.Call call = lastCashIn();
        assertThat(call.amount()).isEqualTo(45_000);
        assertThat(call.customerWallet()).isEqualTo(traderWallet);
        String ref = (String) deposit().get("reference");
        assertThat(lastTextTo(agentPhone, 1)).isEqualTo("Msika: deposit K450 for " + stallCode + " sent. Ref " + ref + ".");
        assertThat(deposit()).containsEntry("status", "PENDING");

        callback("tx-" + UUID.randomUUID(), "SUCCEEDED", 45_000, call.reference());

        assertThat(deposit()).containsEntry("status", "CONFIRMED");
        assertThat(ledger()).containsExactly("DEPOSIT:DEPOSITED:45000");
        assertThat(lastTextTo(traderPhone, 1)).isEqualTo("Msika: K450 deposited at " + stallCode + " by agent Mwila. Ref "
                + ref + ". Reply NO " + ref + " if this is wrong.");
    }

    @Test
    @DisplayName("NO + ref from the trader flags the confirmed deposit; the full code works for DEP too")
    void traderDisputes() {
        text(agentPhone, "DEP " + stallCode + " 120.50");
        callback("tx-" + UUID.randomUUID(), "SUCCEEDED", 12_050, lastCashIn().reference());
        String ref = (String) deposit().get("reference");

        text(traderPhone, "no " + ref.toLowerCase());

        assertThat(deposit()).containsEntry("status", "DISPUTED");
        assertThat(lastTextTo(traderPhone, 2)).isEqualTo("Msika: deposit " + ref + " is flagged. The market office will contact you.");
        text(traderPhone, "NO 2222");
        assertThat(templates(traderPhone).get(2)).isEqualTo("REF_NOT_FOUND");
    }

    @Test
    @DisplayName("a failed payment tells the agent why, and nothing is recorded")
    void failedDeposit() {
        text(agentPhone, "DEP B114 450");
        callback("tx-" + UUID.randomUUID(), "FAILED", 45_000, lastCashIn().reference());

        String ref = (String) deposit().get("reference");
        assertThat(deposit()).containsEntry("status", "FAILED");
        assertThat(ledger()).isEmpty();
        assertThat(lastTextTo(agentPhone, 2)).isEqualTo("Msika: deposit " + ref + " FAILED: not enough float. Cash not recorded.");
    }

    @Test
    @DisplayName("UNDO by the agent within 10 minutes cancels and tells the trader; another agent cannot undo it")
    void undo() {
        text(agentPhone, "DEP B114 450");
        String ref = (String) deposit().get("reference");
        lastTextTo(agentPhone, 1);

        String otherAgent = phone("+260955");
        api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/agents",
                new RegisterAgentRequest(otherAgent, "Other"), AgentBody.class);
        text(otherAgent, "UNDO " + ref);
        assertThat(templates(otherAgent)).containsExactly("REF_NOT_FOUND");

        text(agentPhone, "UNDO " + ref);

        assertThat(deposit()).containsEntry("status", "CANCELLED");
        assertThat(lastTextTo(agentPhone, 2)).isEqualTo("Msika: deposit " + ref + " cancelled. The trader has been told.");
        assertThat(lastTextTo(traderPhone, 1)).contains("deposit " + ref + " of K450 at " + stallCode + " was cancelled");
        text(agentPhone, "UNDO " + ref);
        assertThat(lastTextTo(agentPhone, 3)).contains("cannot be cancelled: it is already cancelled");
    }

    @Test
    @DisplayName("a deposit the use case refuses is answered with the reason, and nothing is left behind")
    void refusedDeposit() {
        ownerJdbc().sql("delete from payments_wallet where wallet_id = :w").param("w", traderWallet).update();

        text(agentPhone, "DEP B114 450");

        assertThat(lastTextTo(agentPhone, 1)).startsWith("Msika: not done: stall " + stallCode + " has no trader wallet");
        assertThat(ownerJdbc().sql("select count(*) from deposit where council_id = :c").param("c", council.councilId())
                .query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("select count(*) from payment_request where council_id = :c").param("c", council.councilId())
                .query(Long.class).single()).isZero();
        text(agentPhone, "DEP Z999 450");
        assertThat(templates(agentPhone).get(1)).isEqualTo("STALL_NOT_FOUND");
    }

    @Test
    @DisplayName("ROUND gives the agent's confirmed deposits today and their float")
    void round() {
        payments.balances.put(agentWallet, 230_000L);
        text(agentPhone, "DEP B114 450");
        callback("tx-" + UUID.randomUUID(), "SUCCEEDED", 45_000, lastCashIn().reference());

        text(agentPhone, "ROUND");

        assertThat(lastTextTo(agentPhone, 2)).isEqualTo("Msika agent Mwila today: 1 deposits, K450. Float K2,300.");
    }

    // -------------------------------------------------------------------------------------------------------------
    // Trader commands
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SALE records a DECLARED sale with a reference, and TODAY keeps each kind apart")
    void saleAndToday() {
        text(traderPhone, "SALE 120");
        String first = lastTextTo(traderPhone, 1);
        assertThat(first).matches("Msika: sale K120 noted\\. Today declared K120\\. Ref [23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}\\.");
        text(traderPhone, "S K1,000.50");
        assertThat(lastTextTo(traderPhone, 2)).contains("sale K1,000.50 noted. Today declared K1,120.50.");
        assertThat(ledger()).containsExactly("DECLARED_SALE:DECLARED:12000", "DECLARED_SALE:DECLARED:100050");

        text(agentPhone, "DEP B114 450");
        callback("tx-" + UUID.randomUUID(), "SUCCEEDED", 45_000, lastCashIn().reference());
        text(traderPhone, "TODAY");

        assertThat(outbox(traderPhone)).last().isEqualTo("Msika " + stallCode
                + " today: deposited K450, digital K0, declared K1,120.50, levy paid K0.");
    }

    @Test
    @DisplayName("BAL reads the wallet from the payments system; STOP and START set the preference")
    void balanceAndPreferences() {
        payments.balances.put(traderWallet, 123_000L);
        text(traderPhone, "BAL");
        assertThat(lastTextTo(traderPhone, 1)).isEqualTo("Msika: wallet K1,230.00.");

        text(traderPhone, "STOP");
        assertThat(ownerJdbc().sql("select daily_summaries from trader where msisdn = :p").param("p", traderPhone)
                .query(Boolean.class).single()).isFalse();
        text(traderPhone, "START");
        assertThat(ownerJdbc().sql("select daily_summaries from trader where msisdn = :p").param("p", traderPhone)
                .query(Boolean.class).single()).isTrue();
        text(traderPhone, "LEVY");
        text(traderPhone, "MONTH");
        text(traderPhone, "LOAN");
        assertThat(templates(traderPhone)).containsExactly("BALANCE_WALLET", "STOPPED", "STARTED", "LEVY_UNKNOWN",
                "NO_STATEMENT", "LOAN_NONE");
    }

    @Test
    @DisplayName("REG from a new number raises a request; approval registers and allocates; YES records consent once")
    void registrationWithConsent() {
        String newcomer = phone("+260955");
        String code = marketCode + "-B-115";

        text(newcomer, "REG " + code.toLowerCase());
        text(newcomer, "REG " + code);

        assertThat(templates(newcomer)).containsExactly("REG_SENT", "REG_SENT");
        assertThat(outbox(newcomer).get(0)).isEqualTo("Msika: request sent for stall " + code + ". The market office will confirm.");
        RegistrationBody[] pending = api().get(council.officerToken(),
                "/api/v1/councils/" + council.councilId() + "/registrations", RegistrationBody[].class).body();
        assertThat(pending).hasSize(1);
        assertThat(pending[0].status()).isEqualTo("PENDING");
        assertThat(pending[0].phone()).isEqualTo(newcomer);

        String approve = "/api/v1/councils/" + council.councilId() + "/registrations/" + pending[0].id() + "/approve";
        assertThat(api().post(council.officerToken(), approve, new ApproveRequest("New Trader", null), String.class).status())
                .isEqualTo(403);
        var approved = api().post(council.adminToken(), approve, new ApproveRequest("New Trader", null), RegistrationBody.class);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().status()).isEqualTo("APPROVED");
        assertThat(api().post(council.adminToken(), approve, new ApproveRequest("New Trader", null), String.class).status())
                .isEqualTo(409);
        assertThat(templates(newcomer)).last().isEqualTo("REG_APPROVED");
        assertThat(ownerJdbc().sql("select status from stall where id = :s").param("s", vacantStallId)
                .query(String.class).single()).isEqualTo("ALLOCATED");

        text(newcomer, "YES");
        assertThat(outbox(newcomer)).last().isEqualTo("Msika: you are registered at " + code
                + ". Levy will be paid from your deposits. Send HELP for commands.");
        assertThat(ownerJdbc().sql("select consent_levy_sweep_at is not null from trader where msisdn = :p")
                .param("p", newcomer).query(Boolean.class).single()).isTrue();
        text(newcomer, "YES");
        assertThat(templates(newcomer)).last().isEqualTo("NOTHING_TO_CONSENT");
    }

    @Test
    @DisplayName("a rejected registration is texted with no change to the stall; REG for an unknown stall is refused")
    void registrationRejected() {
        String newcomer = phone("+260955");
        text(newcomer, "REG " + marketCode + "-B-115");
        RegistrationBody[] pending = api().get(council.adminToken(),
                "/api/v1/councils/" + council.councilId() + "/registrations", RegistrationBody[].class).body();

        var rejected = api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/registrations/"
                + pending[0].id() + "/reject", new RejectRequest("stall is reserved"), RegistrationBody.class);

        assertThat(rejected.body().status()).isEqualTo("REJECTED");
        assertThat(templates(newcomer)).containsExactly("REG_SENT", "REG_REJECTED");
        assertThat(ownerJdbc().sql("select status from stall where id = :s").param("s", vacantStallId)
                .query(String.class).single()).isEqualTo("VACANT");
        text(newcomer, "REG ZZZ-Q-1");
        assertThat(templates(newcomer)).last().isEqualTo("STALL_NOT_FOUND");
        assertThat(ownerJdbc().sql("select outcome from sms_inbound where from_msisdn = :p order by stored_at desc limit 1")
                .param("p", newcomer).query(String.class).single()).isEqualTo("STALL_NOT_FOUND");
    }
}
