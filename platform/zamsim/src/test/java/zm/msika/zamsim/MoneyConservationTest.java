package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.zamsim.wallet.WalletService;
import zm.msika.zamsim.wallet.Wallets.Transaction;
import zm.msika.zamsim.wallet.Wallets.TransactionStatus;
import zm.msika.zamsim.wallet.Wallets.Wallet;
import zm.msika.zamsim.wallet.Wallets.WalletType;

/**
 * The invariants of a money system, checked from the raw ledger and not from what the service says:
 * balances never go negative, money is conserved, and every balance is exactly the sum of its ledger lines.
 */
class MoneyConservationTest extends ZamsimIntegrationTest {

    @Autowired
    WalletService wallets;

    @Autowired
    JdbcClient jdbc;

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    /** Creates {@code count} trader wallets, each funded with {@code each} ngwee. */
    private List<Wallet> funded(int count, long each) {
        List<Wallet> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Wallet w = wallets.createWallet(WalletType.TRADER, "Conservation " + i, null);
            wallets.fund(key(), w.id(), each, "seed");
            created.add(w);
        }
        return created;
    }

    private long balance(UUID id) {
        return jdbc.sql("select balance_ngwee from wallet where id = :id").param("id", id).query(Long.class).single();
    }

    private long sumOfBalances(List<Wallet> group) {
        return group.stream().mapToLong(w -> balance(w.id())).sum();
    }

    /** Every balance must equal the sum of that wallet's ledger lines, and the last line's balance_after. */
    private void assertBalancesMatchTheLedger(List<Wallet> group) {
        for (Wallet w : group) {
            long fromLines = jdbc.sql("select coalesce(sum(amount_ngwee), 0) from ledger_line where wallet_id = :id")
                    .param("id", w.id()).query(Long.class).single();
            assertThat(balance(w.id())).as("wallet %s: balance equals the sum of its ledger lines", w.name())
                    .isEqualTo(fromLines);
            Long lastAfter = jdbc.sql("select balance_after_ngwee from ledger_line where wallet_id = :id order by id desc limit 1")
                    .param("id", w.id()).query(Long.class).optional().orElse(0L);
            assertThat(lastAfter).as("wallet %s: the last line's running balance", w.name()).isEqualTo(balance(w.id()));
            assertThat(balance(w.id())).as("wallet %s is never negative", w.name()).isGreaterThanOrEqualTo(0);
        }
    }

    private long totalOfAllWalletsIncludingIssuer() {
        return jdbc.sql("select sum(balance_ngwee) from wallet").query(Long.class).single();
    }

    private long unbalancedTransactions() {
        return jdbc.sql("select count(*) from (select transaction_id from ledger_line group by transaction_id "
                + "having sum(amount_ngwee) <> 0) x").query(Long.class).single();
    }

    @Test
    @DisplayName("1,000 random transfers: nothing is created or destroyed, no balance is negative, every balance matches its ledger")
    void thousandRandomTransfersConserveMoney() {
        List<Wallet> group = funded(12, 50_000);
        long totalBefore = sumOfBalances(group);
        long grandTotalBefore = totalOfAllWalletsIncludingIssuer();
        Random random = new Random(20261123L);
        int succeeded = 0;
        int failed = 0;

        for (int i = 0; i < 1_000; i++) {
            Wallet from = group.get(random.nextInt(group.size()));
            Wallet to = group.get(random.nextInt(group.size()));
            if (from.id().equals(to.id())) {
                continue;
            }
            long amount = 1 + random.nextInt(60_000); // often more than the wallet holds, so some must fail
            Transaction tx = wallets.transfer(key(), from.id(), to.id(), amount, "random-" + i);
            if (tx.status() == TransactionStatus.SUCCEEDED) {
                succeeded++;
            } else {
                failed++;
            }
            if (i % 100 == 0) {
                assertThat(sumOfBalances(group)).as("after %d transfers", i).isEqualTo(totalBefore);
            }
        }

        assertThat(succeeded).as("plenty of transfers really moved money").isGreaterThan(300);
        assertThat(failed).as("and plenty failed for lack of funds, so that path was exercised too").isGreaterThan(50);
        assertThat(sumOfBalances(group)).as("the total of these wallets is unchanged").isEqualTo(totalBefore);
        assertThat(totalOfAllWalletsIncludingIssuer()).as("and the whole system, issuer included, is unchanged")
                .isEqualTo(grandTotalBefore);
        assertThat(unbalancedTransactions()).as("every transaction's ledger lines add up to zero").isZero();
        assertBalancesMatchTheLedger(group);
    }

    @Test
    @DisplayName("1,000 random transfers from eight threads at once, in both directions: still conserved, still no negative balance, no deadlock")
    void concurrentRandomTransfersConserveMoney() throws Exception {
        List<Wallet> group = funded(10, 40_000);
        long totalBefore = sumOfBalances(group);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                long seed = 1_000 + t;
                tasks.add(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; i < 125; i++) {
                        Wallet from = group.get(random.nextInt(group.size()));
                        Wallet to = group.get(random.nextInt(group.size()));
                        if (from.id().equals(to.id())) {
                            continue;
                        }
                        Transaction tx = wallets.transfer(key(), from.id(), to.id(), 1 + random.nextInt(50_000), null);
                        (tx.status() == TransactionStatus.SUCCEEDED ? succeeded : failed).incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks, 120, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get(); // rethrows any failure, including a deadlock timeout
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isGreaterThan(200);
        assertThat(failed.get()).isGreaterThan(20);
        assertThat(sumOfBalances(group)).isEqualTo(totalBefore);
        assertThat(unbalancedTransactions()).isZero();
        assertBalancesMatchTheLedger(group);
    }

    @Test
    @DisplayName("fifty threads all spending the same 1,000 ngwee: exactly ten of the 100-ngwee payments succeed and the balance ends at exactly zero")
    void aBalanceCanNeverBeOverspent() throws Exception {
        Wallet spender = funded(1, 1_000).get(0);
        Wallet receiver = wallets.createWallet(WalletType.TRADER, "Receiver", null);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Transaction>> tasks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                tasks.add(() -> wallets.transfer(key(), spender.id(), receiver.id(), 100, null));
            }
            long successes = 0;
            for (Future<Transaction> f : pool.invokeAll(tasks)) {
                if (f.get().status() == TransactionStatus.SUCCEEDED) {
                    successes++;
                }
            }

            assertThat(successes).as("only as many as the money covers").isEqualTo(10);
            assertThat(balance(spender.id())).isZero();
            assertThat(balance(receiver.id())).isEqualTo(1_000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("transfers in opposite directions between the same two wallets, many at once, never deadlock")
    void oppositeTransfersDoNotDeadlock() throws Exception {
        List<Wallet> pair = funded(2, 1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Transaction>> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                boolean forward = i % 2 == 0;
                Wallet from = forward ? pair.get(0) : pair.get(1);
                Wallet to = forward ? pair.get(1) : pair.get(0);
                tasks.add(() -> wallets.transfer(key(), from.id(), to.id(), 10, null));
            }
            for (Future<Transaction> f : pool.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
                assertThat(f.get().status()).isEqualTo(TransactionStatus.SUCCEEDED);
            }
            assertThat(sumOfBalances(pair)).isEqualTo(2_000_000);
            assertThat(balance(pair.get(0).id())).as("100 each way, so back where they started").isEqualTo(1_000_000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("eight threads sending the same Idempotency-Key at once give exactly one transaction, round after round")
    void concurrentSameKey() throws Exception {
        List<Wallet> pair = funded(2, 100_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 15; round++) {
                String k = key();
                List<Callable<Transaction>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(() -> wallets.transfer(k, pair.get(0).id(), pair.get(1).id(), 100, "race"));
                }
                List<UUID> ids = new ArrayList<>();
                for (Future<Transaction> f : pool.invokeAll(tasks)) {
                    ids.add(f.get().id());
                }
                assertThat(ids).as("round %d: every call got the same transaction", round).containsOnly(ids.get(0));
            }
            assertThat(balance(pair.get(1).id())).as("moved once per round, 15 times").isEqualTo(100_000 + 15 * 100);
            assertThat(balance(pair.get(0).id())).isEqualTo(100_000 - 15 * 100);
        } finally {
            pool.shutdownNow();
        }
    }
}
