package zm.msika.api.levy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import zm.msika.api.ApiTest;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.levy.LevyAuthorityPort;
import zm.msika.core.levy.LevyAuthorityUnavailableException;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.payments.StatementLine;
import zm.msika.ledger.Money;

/**
 * The levy sweep (card M-24) from a confirmed deposit to the levy authority, with the payments system and the
 * authority played by scripts. Callbacks are signed and posted to the real endpoint.
 */
class LevySweepApiTest extends ApiTest {

    /** Records cash-ins and transfers; every call gets a transaction id from its key. */
    static final class ScriptedPayments implements PaymentsPort {
        record Call(String kind, String key, String from, String to, long amount, String reference) {}

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String cashIn(String key, String agent, String customer, Money amount, String reference) {
            calls.add(new Call("CASH_IN", key, agent, customer, amount.minorUnits(), reference));
            return "tx-" + key;
        }

        @Override
        public String transfer(String key, String from, String to, Money amount, String reference) {
            calls.add(new Call("TRANSFER", key, from, to, amount.minorUnits(), reference));
            return "tx-" + key;
        }

        @Override
        public Optional<PaymentsWallet> findWallet(String walletId) {
            return Optional.empty();
        }

        @Override
        public List<StatementLine> statement(String walletId, LocalDate from, LocalDate to) {
            return List.of();
        }
    }

    /** An authority that credits each key once, and can be told to be down for the next few calls. */
    static final class ScriptedAuthority implements LevyAuthorityPort {
        final Map<String, Long> credited = new ConcurrentHashMap<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger down = new AtomicInteger();
        final Set<String> opened = ConcurrentHashMap.newKeySet();

        @Override
        public String openAccount(String councilRef, String marketRef, String traderRef, long daily) {
            opened.add(marketRef + "/" + traderRef);
            return "acc-" + traderRef;
        }

        @Override
        public void topUp(String accountId, String key, long amount, String reference) {
            attempts.incrementAndGet();
            if (down.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new LevyAuthorityUnavailableException("scripted outage", null);
            }
            credited.putIfAbsent(accountId + "|" + key, amount);
        }

        @Override
        public Optional<LevyAccount> account(String accountId) {
            return Optional.empty();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedPayments scriptedPayments() {
            return new ScriptedPayments();
        }

        @Bean
        @Primary
        ScriptedAuthority scriptedAuthority() {
            return new ScriptedAuthority();
        }
    }

    record RuleRequest(String stallClass, Long amountNgwee, String validFrom) {}

    record RunRequest(String date) {}

    record StartRequest(UUID agentId, UUID stallId, long amountNgwee) {}

    record DepositBody(UUID id, String status) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ScriptedPayments payments;

    @Autowired
    ScriptedAuthority authority;

    @Autowired
    LevySweep sweep;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    CallbackVerifier verifier;

    @Autowired
    Clock clock;

    private Fixture council;
    private UUID market;
    private UUID stallId;
    private UUID traderId;
    private UUID agentId;
    private String tag;

    @BeforeEach
    void setUp() {
        council = newCouncil();
        market = UUID.randomUUID();
        stallId = UUID.randomUUID();
        traderId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        tag = UUID.randomUUID().toString().substring(0, 8);
        UUID c = council.councilId();
        String digits = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        tenant.inCouncil(c, () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'SWP', 'Sweep Market', '20:00', now())")
                    .param("id", market).param("c", c).update();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'SWP-A-1', 'STANDARD', 'ALLOCATED', :t)")
                    .param("id", stallId).param("c", c).param("m", market)
                    .param("t", UUID.randomUUID().toString().replace("-", "")).update();
            jdbc.sql("insert into trader (id, council_id, msisdn, display_name, consent_levy_sweep_at) "
                            + "values (:id, :c, :p, 'Sweep Trader', now())")
                    .param("id", traderId).param("c", c).param("p", "+260966" + digits).update();
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) "
                            + "values (:id, :c, :s, :t, :from)")
                    .param("id", UUID.randomUUID()).param("c", c).param("s", stallId).param("t", traderId)
                    .param("from", OffsetDateTime.parse("2026-11-01T08:00:00+02:00")).update();
            jdbc.sql("insert into agent (id, council_id, msisdn, display_name, active, created_at) "
                            + "values (:id, :c, :p, 'Sweep Agent', true, now())")
                    .param("id", agentId).param("c", c).param("p", "+260977" + digits).update();
            for (Object[] w : new Object[][] {{"TRADER", stallId, null}, {"AGENT", null, agentId}, {"COUNCIL", null, null}}) {
                jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, stall_id, agent_id) values (:w, :c, :r, :s, :a)")
                        .param("w", "w-" + w[0].toString().toLowerCase() + "-" + tag).param("c", c).param("r", w[0])
                        .param("s", w[1]).param("a", w[2]).update();
            }
        });
        // K5 a day; three unpaid days.
        api().post(council.adminToken(), "/api/v1/councils/" + c + "/markets/" + market + "/levy-rules",
                new RuleRequest("STANDARD", 500L, "2026-11-01"), String.class);
        for (String day : List.of("2026-12-01", "2026-12-02", "2026-12-03")) {
            api().post(council.adminToken(), "/api/v1/councils/" + c + "/levy-charges/run", new RunRequest(day), String.class);
        }
    }

    private String wallet(String role) {
        return "w-" + role.toLowerCase() + "-" + tag;
    }

    private void callback(ScriptedPayments.Call call, String status) {
        String txn = "tx-" + call.key();
        String json = "{\"callbackId\":\"cb-" + txn + "-" + status + "\",\"event\":\"transaction.completed\",\"transaction\":{"
                + "\"id\":\"" + txn + "\",\"kind\":\"" + call.kind() + "\",\"status\":\"" + status + "\",\"amountNgwee\":"
                + call.amount() + ",\"fromWalletId\":\"" + call.from() + "\",\"toWalletId\":\"" + call.to() + "\","
                + "\"reference\":\"" + call.reference() + "\","
                + "\"failureReason\":" + ("FAILED".equals(status) ? "\"INSUFFICIENT_FUNDS\"" : "null") + ","
                + "\"completedAt\":\"" + Instant.now() + "\"}}";
        long now = clock.instant().getEpochSecond();
        try {
            int code = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/payments/callback"))
                    .header("Content-Type", "application/json").header("X-Timestamp", Long.toString(now))
                    .header("X-Signature", verifier.sign(now, json.getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            assertThat(code).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An agent's deposit, confirmed by the payments system. Returns the cash-in call. */
    private ScriptedPayments.Call confirmedDeposit(long ngwee) {
        var started = api().send(council.adminToken(), HttpMethod.POST, "/api/v1/councils/" + council.councilId() + "/deposits",
                new StartRequest(agentId, stallId, ngwee), MediaType.APPLICATION_JSON, DepositBody.class, "dep-" + UUID.randomUUID());
        assertThat(started.status()).isEqualTo(202);
        ScriptedPayments.Call cashIn = last("CASH_IN");
        callback(cashIn, "SUCCEEDED");
        return cashIn;
    }

    private ScriptedPayments.Call last(String kind) {
        return payments.calls.stream().filter(c -> c.kind().equals(kind) && c.to().contains(tag) || c.kind().equals(kind)
                && c.from().contains(tag)).reduce((a, b) -> b).orElseThrow();
    }

    private long transfers() {
        return payments.calls.stream().filter(c -> c.kind().equals("TRANSFER") && c.from().contains(tag)).count();
    }

    private List<Map<String, Object>> instructions() {
        return ownerJdbc().sql("select status, amount_ngwee, attempts from payment_instruction where council_id = :c order by created_at")
                .param("c", council.councilId()).query().listOfRows();
    }

    private List<String> unpaidDays() {
        return ownerJdbc().sql("select business_date::text from levy_charge where council_id = :c "
                        + "and settled_by_instruction_id is null order by business_date")
                .param("c", council.councilId()).query(String.class).list();
    }

    private List<String> ledger() {
        return ownerJdbc().sql("select kind || ':' || provenance || ':' || amount_ngwee from ledger_entry where council_id = :c order by id")
                .param("c", council.councilId()).query(String.class).list();
    }

    private long credited() {
        return authority.credited.entrySet().stream().filter(e -> e.getKey().startsWith("acc-" + traderId))
                .mapToLong(Map.Entry::getValue).sum();
    }

    @Test
    @DisplayName("a deposit that covers the levy owed pays every day: transfer, LEVY_PAYMENT on the ledger, eLevy credited once")
    void fullSweep() {
        confirmedDeposit(45_000);
        assertThat(instructions()).singleElement().satisfies(i -> assertThat(i).containsEntry("status", "PENDING")
                .containsEntry("amount_ngwee", 1_500L));
        assertThat(unpaidDays()).isEmpty();

        sweep.runOnce();
        ScriptedPayments.Call transfer = last("TRANSFER");
        assertThat(transfer.from()).isEqualTo(wallet("TRADER"));
        assertThat(transfer.to()).isEqualTo(wallet("COUNCIL"));
        assertThat(transfer.amount()).isEqualTo(1_500);
        callback(transfer, "SUCCEEDED");
        assertThat(instructions().get(0)).containsEntry("status", "MOVED");
        sweep.runOnce();

        assertThat(instructions().get(0)).containsEntry("status", "DONE");
        assertThat(ledger()).containsExactly("DEPOSIT:DEPOSITED:45000", "LEVY_PAYMENT:VERIFIED:1500");
        assertThat(credited()).isEqualTo(1_500);
        assertThat(authority.opened).contains(market + "/" + traderId);
        sweep.runOnce();
        assertThat(transfers()).as("nothing is sent twice").isEqualTo(1);
    }

    @Test
    @DisplayName("a deposit smaller than the levy owed pays the oldest whole days; smaller than one day pays nothing")
    void smallerThanOwed() {
        confirmedDeposit(1_200);
        assertThat(instructions()).singleElement().satisfies(i -> assertThat(i).containsEntry("amount_ngwee", 1_000L));
        assertThat(unpaidDays()).containsExactly("2026-12-03");

        confirmedDeposit(300);
        assertThat(instructions()).as("K3 does not cover a K5 day").hasSize(1);
        assertThat(unpaidDays()).containsExactly("2026-12-03");
    }

    @Test
    @DisplayName("a transfer that fails records nothing, tells nobody, and frees the days for the next deposit")
    void transferFails() {
        confirmedDeposit(45_000);
        sweep.runOnce();
        callback(last("TRANSFER"), "FAILED");

        assertThat(instructions().get(0)).containsEntry("status", "FAILED");
        assertThat(unpaidDays()).containsExactly("2026-12-01", "2026-12-02", "2026-12-03");
        assertThat(ledger()).containsExactly("DEPOSIT:DEPOSITED:45000");
        sweep.runOnce();
        assertThat(credited()).isZero();
        assertThat(transfers()).isEqualTo(1);

        confirmedDeposit(45_000);
        assertThat(instructions()).hasSize(2);
        assertThat(unpaidDays()).isEmpty();
    }

    @Test
    @DisplayName("an eLevy top-up that fails after the money moved is retried, and never paid twice")
    void authorityFailsAfterMoney() {
        confirmedDeposit(45_000);
        sweep.runOnce();
        callback(last("TRANSFER"), "SUCCEEDED");
        authority.down.set(2);
        int before = authority.attempts.get();

        sweep.runOnce();
        sweep.runOnce();
        assertThat(instructions().get(0)).containsEntry("status", "MOVED").containsEntry("attempts", 2);
        sweep.runOnce();
        sweep.runOnce();

        assertThat(instructions().get(0)).containsEntry("status", "DONE");
        assertThat(authority.attempts.get() - before).isEqualTo(3);
        assertThat(credited()).isEqualTo(1_500);
        assertThat(transfers()).as("the money moved once").isEqualTo(1);
        assertThat(ledger()).filteredOn(e -> e.startsWith("LEVY_PAYMENT")).hasSize(1);
    }

    @Test
    @DisplayName("a trader who has not consented has nothing swept")
    void noConsent() {
        ownerJdbc().sql("update trader set consent_levy_sweep_at = null where id = :t").param("t", traderId).update();

        confirmedDeposit(45_000);
        sweep.runOnce();

        assertThat(instructions()).isEmpty();
        assertThat(transfers()).isZero();
        assertThat(unpaidDays()).hasSize(3);
    }

    @Test
    @DisplayName("the same confirmation arriving twice makes one sweep")
    void duplicateConfirmation() {
        ScriptedPayments.Call cashIn = confirmedDeposit(45_000);
        callback(cashIn, "SUCCEEDED");

        assertThat(instructions()).hasSize(1);
    }
}
