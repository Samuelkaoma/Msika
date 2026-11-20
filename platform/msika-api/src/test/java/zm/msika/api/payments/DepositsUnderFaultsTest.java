package zm.msika.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import zm.msika.api.ApiTest;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.ledger.Currency;
import zm.msika.ledger.Money;

/**
 * The card's proof (M-14): 500 deposits through a REAL zamsim process with every fault turned on, and the ledger
 * ends exactly right.
 *
 * <p>The faults, all at once: zamsim fails some payments, delivers every callback late, delivers many callbacks
 * twice, and holds some back so they arrive out of order. On top of that Msika itself is made unreliable here:
 * some callbacks are refused with a 503 before they are looked at, and some are fully applied but their
 * acknowledgement is turned into a 500 (so zamsim sends them again and Msika must see they are repeats).
 *
 * <p>The answer key is zamsim itself, asked over HTTP: for every request, its transaction's final status and
 * amount, and the balances of the trader wallets. The ledger must agree with it exactly.
 */
class DepositsUnderFaultsTest extends ApiTest {

    private static final int DEPOSITS = 500;
    private static final int STALLS = 3;
    private static final int ZAMSIM_PORT = freePort();

    private static final AtomicInteger REFUSED_BEFORE = new AtomicInteger();
    private static final AtomicInteger ACK_LOST = new AtomicInteger();
    private static final AtomicInteger CALLBACKS_SEEN = new AtomicInteger();

    @DynamicPropertySource
    static void zamsimLocation(DynamicPropertyRegistry registry) {
        registry.add("msika.payments.zamsim.base-url", () -> "http://localhost:" + ZAMSIM_PORT);
    }

    /**
     * Makes Msika's callback endpoint unreliable, for this test only: the filter is a test bean, so it does not
     * exist in the real application. It sits in front of the endpoint like a flaky network and a crashing server.
     */
    @TestConfiguration
    static class FlakyCallbacks {
        private final Random random = new Random(20261124);

        private synchronized double draw() {
            return random.nextDouble();
        }

        @Bean
        OncePerRequestFilter flakyCallbackFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return !request.getRequestURI().equals("/api/v1/payments/callback");
                }

                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    CALLBACKS_SEEN.incrementAndGet();
                    double roll = draw();
                    if (roll < 0.15) {
                        REFUSED_BEFORE.incrementAndGet();
                        response.sendError(503);
                        return;
                    }
                    if (roll < 0.30) {
                        // Applied in full, but the sender never learns it: its next try must be recognised as a repeat.
                        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
                        chain.doFilter(request, wrapper);
                        ACK_LOST.incrementAndGet();
                        wrapper.setStatus(500);
                        wrapper.copyBodyToResponse();
                        return;
                    }
                    chain.doFilter(request, response);
                }
            };
        }
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    PaymentService payments;

    private record Wallet(UUID id) {}

    private record Tx(UUID id, String status, long amountNgwee, String reference) {}

    private record WalletBody(UUID id, long balanceNgwee) {}

    private record Faults(double failureRate, int callbackDelayMs, double duplicateCallbackRate, double reorderRate,
            int reorderExtraDelayMs) {}

    private record CreateWallet(String type, String name, String msisdn) {}

    private record Fund(UUID walletId, long amountNgwee, String reference) {}

    private record CallbackRow(String callbackId, String status) {}

    @Test
    @DisplayName("500 deposits with every fault on end with exactly the right ledger: nothing lost, nothing twice")
    void fiveHundredDeposits() throws Exception {
        Path log = Files.createTempFile("zamsim-run", ".log");
        Process zamsim = startZamsim(log);
        try {
            RestClient sim = RestClient.create("http://localhost:" + ZAMSIM_PORT);
            waitForHealth(sim);

            // The cast: one agent with plenty of float, and a trader wallet for each of three stalls in three councils.
            Wallet agent = wallet(sim, "AGENT", "Test Agent");
            sim.post().uri("/v1/admin/fund").header("Idempotency-Key", "fund-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Fund(agent.id(), 1_000_000_000L, "float")).retrieve().toBodilessEntity();
            List<PaymentTestSupport.Stall> stalls = new ArrayList<>();
            Map<UUID, Wallet> traderWallets = new HashMap<>();
            for (int i = 0; i < STALLS; i++) {
                PaymentTestSupport.Stall stall = PaymentTestSupport.newStall(jdbc, tenant);
                stalls.add(stall);
                traderWallets.put(stall.stallId(), wallet(sim, "TRADER", "Trader " + i));
            }

            // Every fault on, all at once.
            sim.put().uri("/v1/admin/faults").contentType(MediaType.APPLICATION_JSON)
                    .body(new Faults(0.20, 50, 0.30, 0.30, 400)).retrieve().toBodilessEntity();

            // 500 deposits, 8 at a time, of random amounts, spread over the stalls.
            Random random = new Random(500);
            List<Callable<UUID>> jobs = new ArrayList<>();
            for (int i = 0; i < DEPOSITS; i++) {
                PaymentTestSupport.Stall stall = stalls.get(random.nextInt(STALLS));
                long ngwee = 100 + random.nextInt(9_900);
                jobs.add(() -> payments.startDeposit(stall.councilId(), stall.stallId(), Money.ofMinor(ngwee, Currency.ZMW),
                        agent.id().toString(), traderWallets.get(stall.stallId()).id().toString()).id());
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<UUID> requestIds = new ArrayList<>();
            try {
                for (Future<UUID> f : pool.invokeAll(jobs)) {
                    requestIds.add(f.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(requestIds).hasSize(DEPOSITS).doesNotHaveDuplicates();

            // Wait for every request to reach an outcome. Callbacks retry until Msika accepts them.
            waitUntil(Duration.ofSeconds(120), () -> ownerJdbc()
                    .sql("select count(*) from payment_request where status = 'SENT' and council_id in (:councils)")
                    .param("councils", stalls.stream().map(PaymentTestSupport.Stall::councilId).toList())
                    .query(Long.class).single() == 0L);

            verify(sim, stalls, traderWallets);
        } finally {
            zamsim.destroy();
            zamsim.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (zamsim.isAlive()) {
                zamsim.destroyForcibly();
            }
        }
    }

    private void verify(RestClient sim, List<PaymentTestSupport.Stall> stalls, Map<UUID, Wallet> traderWallets) {
        List<UUID> councils = stalls.stream().map(PaymentTestSupport.Stall::councilId).toList();

        // 1. Msika's own view of the requests.
        var requests = ownerJdbc().sql("select id, council_id, stall_id, amount_ngwee, status, payments_transaction_id "
                        + "from payment_request where council_id in (:c)")
                .param("c", councils).query().listOfRows();
        assertThat(requests).as("every request is saved").hasSize(DEPOSITS);

        long succeeded = 0;
        long failed = 0;
        Map<UUID, Long> expectedPerStall = new HashMap<>();
        for (var row : requests) {
            String status = (String) row.get("status");
            String txn = (String) row.get("payments_transaction_id");
            UUID stallId = (UUID) row.get("stall_id");
            long amount = (Long) row.get("amount_ngwee");

            // 2. The answer key: what zamsim says really happened to that payment.
            assertThat(txn).as("every request learned its transaction id").isNotNull();
            Tx truth = sim.get().uri("/v1/transactions/" + txn).retrieve().body(Tx.class);
            assertThat(truth).isNotNull();
            assertThat(truth.status()).as("zamsim's final status for " + txn).isIn("SUCCEEDED", "FAILED");
            assertThat(status).as("Msika's status for " + txn).isEqualTo(truth.status());
            assertThat(truth.amountNgwee()).isEqualTo(amount);
            assertThat(truth.reference()).as("the reference round-trips").isEqualTo(
                    zm.msika.core.payments.PaymentReference.of((UUID) row.get("council_id"), (UUID) row.get("id")));

            // 3. The ledger: exactly one entry for a payment that happened, none for one that did not.
            var entries = ownerJdbc().sql("select kind, provenance, amount_ngwee, stall_id from ledger_entry "
                            + "where council_id = :c and idempotency_key = :k")
                    .param("c", row.get("council_id")).param("k", "zamsim:" + txn).query().listOfRows();
            if (truth.status().equals("SUCCEEDED")) {
                succeeded++;
                assertThat(entries).as("exactly one ledger entry for succeeded payment " + txn).hasSize(1);
                assertThat(entries.get(0)).containsEntry("kind", "DEPOSIT").containsEntry("provenance", "DEPOSITED")
                        .containsEntry("amount_ngwee", amount).containsEntry("stall_id", stallId);
                expectedPerStall.merge(stallId, amount, Long::sum);
            } else {
                failed++;
                assertThat(entries).as("no ledger entry for failed payment " + txn).isEmpty();
            }
        }

        // 4. Nothing else is on these councils' ledgers: no extras, no duplicates.
        assertThat(ownerJdbc().sql("select count(*) from ledger_entry where council_id in (:c)").param("c", councils)
                .query(Long.class).single()).as("ledger entries").isEqualTo(succeeded);

        // 5. Money agrees with the payments system, wallet by wallet.
        for (PaymentTestSupport.Stall stall : stalls) {
            long ledgerSum = ownerJdbc().sql("select coalesce(sum(amount_ngwee), 0) from ledger_entry where stall_id = :s")
                    .param("s", stall.stallId()).query(Long.class).single();
            long walletBalance = sim.get().uri("/v1/wallets/" + traderWallets.get(stall.stallId()).id())
                    .retrieve().body(WalletBody.class).balanceNgwee();
            assertThat(ledgerSum).as("ledger sum of stall equals what zamsim says it holds").isEqualTo(walletBalance);
            assertThat(ledgerSum).isEqualTo(expectedPerStall.getOrDefault(stall.stallId(), 0L));
        }

        // 6. The faults really happened, so this was not an easy day. (A test that passes because nothing went wrong proves nothing.)
        List<CallbackRow> sent = List.of(sim.get().uri("/v1/admin/callbacks?limit=1000").retrieve().body(CallbackRow[].class));
        assertThat(sent.stream().filter(c -> c.status().equals("DEAD"))).as("callbacks zamsim gave up on").isEmpty();
        assertThat(succeeded).as("some payments succeeded").isGreaterThan(100);
        assertThat(failed).as("some payments failed, as injected").isGreaterThan(30);
        assertThat(sent.size()).as("duplicate callbacks were sent").isGreaterThan(DEPOSITS);
        assertThat(REFUSED_BEFORE.get()).as("callbacks refused before being read").isGreaterThan(20);
        assertThat(ACK_LOST.get()).as("callbacks applied but not acknowledged").isGreaterThan(20);
        assertThat(CALLBACKS_SEEN.get()).as("Msika saw more callbacks than payments").isGreaterThan(DEPOSITS);
    }

    private static Wallet wallet(RestClient sim, String type, String name) {
        return sim.post().uri("/v1/wallets").contentType(MediaType.APPLICATION_JSON)
                .body(new CreateWallet(type, name + " " + UUID.randomUUID(), null)).retrieve().body(Wallet.class);
    }

    /** Starts zamsim as its own program. The secret and password go in the environment, not on the command line. */
    private Process startZamsim(Path log) throws IOException {
        String jar = System.getProperty("zamsim.jar");
        assertThat(jar).as("system property zamsim.jar (set by the integrationTest task)").isNotNull();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-jar", jar,
                "--server.port=" + ZAMSIM_PORT,
                "--zamsim.callback.max-attempts=40",
                "--zamsim.callback.backoff-ms=100,200,300,500",
                "--zamsim.callback.timeout-ms=3000");
        builder.environment().put("ZAMSIM_DB_URL", "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/zamsim");
        builder.environment().put("ZAMSIM_PASSWORD", "test_zamsim");
        builder.environment().put("ZAMSIM_CALLBACK_URL", "http://localhost:" + port + "/api/v1/payments/callback");
        builder.environment().put("ZAMSIM_CALLBACK_SECRET", CALLBACK_SECRET);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    private static void waitForHealth(RestClient sim) throws InterruptedException {
        waitUntil(Duration.ofSeconds(90), () -> {
            try {
                return sim.get().uri("/health").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private static void waitUntil(Duration limit, java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(limit);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        assertThat(condition.getAsBoolean()).as("condition within " + limit).isTrue();
    }

    /**
     * A port that is free now, taken from 20000-39999. The operating system hands out ports for outgoing
     * connections from a higher range (49152 and up on Windows), and one of those could be taken by this test's own
     * HTTP clients between choosing and binding, so a port from the system's own choice (port 0) is not safe here.
     */
    private static int freePort() {
        java.util.Random random = new java.util.Random();
        for (int attempt = 0; attempt < 200; attempt++) {
            int candidate = 20_000 + random.nextInt(20_000);
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                // taken: try another
            }
        }
        throw new IllegalStateException("no free port found in 20000-39999");
    }
}
