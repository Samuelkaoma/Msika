package zm.msika.api.levy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import zm.msika.api.ApiTest;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.payments.StatementLine;
import zm.msika.ledger.Money;

/**
 * Card M-25: after a seeded week, the {@code LEVY} and {@code BAL} replies and the weekly receipt agree with the levy
 * account in the REAL elevysim, which this test runs as a separate program (like M-14 runs zamsim). The payments
 * system is played by a script.
 */
class LevyWeekApiTest extends ApiTest {

    static final class ScriptedPayments implements PaymentsPort {
        record Call(String kind, String key, String from, String to, long amount, String reference) {}

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        volatile long traderBalance = 400_000;

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
            return Optional.of(new PaymentsWallet(walletId, PaymentsWallet.Kind.TRADER, null, traderBalance));
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

    private static final int ELEVYSIM_PORT = freePort();
    private static Process elevysim;

    @DynamicPropertySource
    static void elevysimLocation(DynamicPropertyRegistry registry) {
        registry.add("msika.levy.elevysim.base-url", () -> "http://localhost:" + ELEVYSIM_PORT);
    }

    @BeforeAll
    static void startElevysim() throws Exception {
        String jar = System.getProperty("elevysim.jar");
        assertThat(jar).as("system property elevysim.jar (set by the integrationTest task)").isNotNull();
        Path log = Files.createTempFile("elevysim-run", ".log");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar, "--server.port=" + ELEVYSIM_PORT, "--elevysim.daily-charge.enabled=false");
        builder.environment().put("ELEVYSIM_DB_URL", "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/elevysim");
        builder.environment().put("ELEVYSIM_PASSWORD", "test_elevysim");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        elevysim = builder.start();
        RestClient client = RestClient.create("http://localhost:" + ELEVYSIM_PORT);
        waitUntil(90, () -> {
            try {
                return client.get().uri("/health").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    @AfterAll
    static void stopElevysim() throws InterruptedException {
        if (elevysim != null) {
            elevysim.destroy();
            elevysim.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (elevysim.isAlive()) {
                elevysim.destroyForcibly();
            }
        }
    }

    private static int freePort() {
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 200; i++) {
            int candidate = 20_000 + random.nextInt(20_000);
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                // taken
            }
        }
        throw new IllegalStateException("no free port");
    }

    private static void waitUntil(int seconds, BooleanSupplier condition) {
        Instant end = Instant.now().plusSeconds(seconds);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("condition not met within " + seconds + " s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    record RuleRequest(String stallClass, Long amountNgwee, String validFrom) {}

    record RunRequest(String date) {}

    record StartRequest(UUID agentId, UUID stallId, long amountNgwee) {}

    record Account(UUID id, long dailyLevyNgwee, long balanceNgwee, long arrearsNgwee, LocalDate paidTo) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ScriptedPayments payments;

    @Autowired
    LevySweep sweep;

    @Autowired
    LevyReceipts receipts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    Clock clock;

    @Autowired
    CallbackVerifier verifier;

    @Autowired
    @Qualifier("smsGatewayVerifier")
    CallbackVerifier smsVerifier;

    private Fixture council;
    private UUID market;
    private UUID stallId;
    private UUID traderId;
    private UUID agentId;
    private String traderPhone;
    private String tag;

    private void seedCouncil(boolean summaries) {
        council = newCouncil();
        market = UUID.randomUUID();
        stallId = UUID.randomUUID();
        traderId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        tag = UUID.randomUUID().toString().substring(0, 8);
        String digits = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        traderPhone = "+260966" + digits;
        UUID c = council.councilId();
        tenant.inCouncil(c, () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'SWP', 'Week Market', '20:00', now())").param("id", market).param("c", c).update();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'SWP-A-1', 'STANDARD', 'ALLOCATED', :t)")
                    .param("id", stallId).param("c", c).param("m", market)
                    .param("t", UUID.randomUUID().toString().replace("-", "")).update();
            jdbc.sql("insert into trader (id, council_id, msisdn, display_name, consent_levy_sweep_at, daily_summaries) "
                            + "values (:id, :c, :p, 'Week Trader', now(), :summaries)")
                    .param("id", traderId).param("c", c).param("p", traderPhone).param("summaries", summaries).update();
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, :f)")
                    .param("id", UUID.randomUUID()).param("c", c).param("s", stallId).param("t", traderId)
                    .param("f", OffsetDateTime.parse("2026-11-01T08:00:00+02:00")).update();
            jdbc.sql("insert into agent (id, council_id, msisdn, display_name, active, created_at) "
                            + "values (:id, :c, :p, 'Week Agent', true, now())")
                    .param("id", agentId).param("c", c).param("p", "+260977" + digits).update();
            for (Object[] w : new Object[][] {{"TRADER", stallId, null}, {"AGENT", null, agentId}, {"COUNCIL", null, null}}) {
                jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, stall_id, agent_id) values (:w, :c, :r, :s, :a)")
                        .param("w", "w-" + w[0].toString().toLowerCase() + "-" + tag).param("c", c).param("r", w[0])
                        .param("s", w[1]).param("a", w[2]).update();
            }
        });
        api().post(council.adminToken(), "/api/v1/councils/" + c + "/markets/" + market + "/levy-rules",
                new RuleRequest("STANDARD", 500L, "2026-11-01"), String.class);
    }

    private void post(String path, String json, String tsHeader, String sigHeader, CallbackVerifier v) {
        long now = clock.instant().getEpochSecond();
        try {
            int code = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json").header(tsHeader, Long.toString(now))
                    .header(sigHeader, v.sign(now, json.getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            assertThat(code).isEqualTo(200);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void callback(ScriptedPayments.Call call) {
        String txn = "tx-" + call.key();
        post("/api/v1/payments/callback", "{\"callbackId\":\"cb-" + txn + "\",\"event\":\"transaction.completed\",\"transaction\":{"
                + "\"id\":\"" + txn + "\",\"kind\":\"" + call.kind() + "\",\"status\":\"SUCCEEDED\",\"amountNgwee\":" + call.amount()
                + ",\"fromWalletId\":\"" + call.from() + "\",\"toWalletId\":\"" + call.to() + "\",\"reference\":\""
                + call.reference() + "\",\"failureReason\":null,\"completedAt\":\"" + Instant.now() + "\"}}",
                "X-Timestamp", "X-Signature", verifier);
    }

    private ScriptedPayments.Call last(String kind) {
        return payments.calls.stream().filter(c -> c.kind().equals(kind) && (c.from().contains(tag) || c.to().contains(tag)))
                .reduce((a, b) -> b).orElseThrow();
    }

    /** A deposit, confirmed, and its levy sweep carried all the way to elevysim. */
    private void depositAndSweep(long ngwee) {
        var started = api().send(council.adminToken(), HttpMethod.POST, "/api/v1/councils/" + council.councilId() + "/deposits",
                new StartRequest(agentId, stallId, ngwee), MediaType.APPLICATION_JSON, String.class, "dep-" + UUID.randomUUID());
        assertThat(started.status()).isEqualTo(202);
        callback(last("CASH_IN"));
        sweep.runOnce();
        callback(last("TRANSFER"));
        sweep.runOnce();
        assertThat(ownerJdbc().sql("select count(*) from payment_instruction where council_id = :c and status <> 'DONE'")
                .param("c", council.councilId()).query(Long.class).single()).as("every sweep reached eLevy").isZero();
    }

    private void text(String body) {
        int before = outbox().size();
        post("/api/v1/sms/inbound", "{\"gatewayMessageId\":\"gw-" + UUID.randomUUID() + "\",\"from\":\"" + traderPhone
                + "\",\"body\":\"" + body + "\",\"receivedAt\":\"" + Instant.now() + "\"}",
                "X-Msika-Timestamp", "X-Msika-Signature", smsVerifier);
        waitUntil(20, () -> outbox().size() > before);
    }

    private List<String> outbox() {
        return ownerJdbc().sql("select body from sms_outbound where to_msisdn = :p order by created_at, id")
                .param("p", traderPhone).query(String.class).list();
    }

    private Account elevysimAccount() {
        String id = ownerJdbc().sql("select account_id from levy_account_link where trader_id = :t")
                .param("t", traderId).query(String.class).single();
        return RestClient.create("http://localhost:" + ELEVYSIM_PORT).get().uri("/v1/levy-accounts/" + id)
                .retrieve().body(Account.class);
    }

    /** Charges one day on both sides: Msika's levy charge and eLevy's own daily charge. */
    private void chargeDay(LocalDate day) {
        api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/levy-charges/run",
                new RunRequest(day.toString()), String.class);
        RestClient.create("http://localhost:" + ELEVYSIM_PORT).post().uri("/v1/admin/run-daily-charge")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("date", day.toString())).retrieve().toBodilessEntity();
    }

    /** 1 to 7 December: K5 a day; K4.50 deposited on the 2nd (pays days 1-2), K12 on the 4th (pays days 3-4). */
    private void seededWeek() {
        // The trader asks LEVY first: that opens their eLevy account before the week starts.
        text("LEVY");
        for (int d = 1; d <= 7; d++) {
            LocalDate day = LocalDate.of(2026, 12, d);
            chargeDay(day);
            if (d == 2) {
                depositAndSweep(45_000);
            }
            if (d == 4) {
                depositAndSweep(1_200);
            }
        }
    }

    @Test
    @DisplayName("after a seeded week, LEVY and BAL show exactly what the eLevy account shows")
    void repliesMatchElevysim() {
        seedCouncil(true);
        seededWeek();

        Account account = elevysimAccount();
        assertThat(account.arrearsNgwee()).as("days 5 to 7 unpaid").isEqualTo(1_500);
        assertThat(account.balanceNgwee()).isZero();
        assertThat(account.paidTo()).isEqualTo(LocalDate.of(2026, 12, 4));

        text("LEVY");
        assertThat(outbox()).last().isEqualTo("Msika: levy K5 a day at SWP-A-1. Paid to 4 Dec. Arrears K15.");
        text("BAL");
        assertThat(outbox()).last().isEqualTo("Msika: wallet K4,000.00. Levy paid to 4 Dec. Arrears K15.");

        // Msika's own record agrees with eLevy's.
        long unpaid = ownerJdbc().sql("select coalesce(sum(amount_ngwee), 0) from levy_charge where trader_id = :t "
                + "and settled_by_instruction_id is null").param("t", traderId).query(Long.class).single();
        assertThat(unpaid).isEqualTo(account.arrearsNgwee());
    }

    @Test
    @DisplayName("the weekly receipt names the days paid, the amount and eLevy's arrears, once per week")
    void weeklyReceipt() {
        seedCouncil(true);
        seededWeek();
        int before = outbox().size();

        int queued = receipts.send(council.councilId(), LocalDate.of(2026, 12, 7));
        int again = receipts.send(council.councilId(), LocalDate.of(2026, 12, 7));

        assertThat(queued).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(outbox()).hasSize(before + 1);
        assertThat(outbox()).last().isEqualTo("Msika: levy paid 1 Dec to 4 Dec at SWP-A-1: K20. Arrears K15. Thank you.");
    }

    @Test
    @DisplayName("a trader who sent STOP gets no weekly receipt")
    void noReceiptAfterStop() {
        seedCouncil(false);
        seededWeek();

        assertThat(receipts.send(council.councilId(), LocalDate.of(2026, 12, 7))).isZero();
    }
}
