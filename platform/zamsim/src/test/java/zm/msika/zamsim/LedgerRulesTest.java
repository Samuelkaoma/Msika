package zm.msika.zamsim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import zm.msika.zamsim.wallet.WalletService;
import zm.msika.zamsim.wallet.Wallets.Transaction;
import zm.msika.zamsim.wallet.Wallets.Wallet;
import zm.msika.zamsim.wallet.Wallets.WalletType;

/**
 * The database enforces zamsim's money rules itself, so a bug in the application code cannot create or
 * destroy money. Each test goes round the service and talks to Postgres directly.
 */
class LedgerRulesTest extends ZamsimIntegrationTest {

    @Autowired
    WalletService wallets;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    private Wallet wallet(long funded) {
        Wallet w = wallets.createWallet(WalletType.TRADER, "Rules " + UUID.randomUUID(), null);
        if (funded > 0) {
            wallets.fund("k-" + UUID.randomUUID(), w.id(), funded, "seed");
        }
        return w;
    }

    @Test
    @DisplayName("the database refuses a negative balance on any ordinary wallet")
    void negativeBalanceRefused() {
        Wallet w = wallet(1_000);

        assertThatThrownBy(() -> jdbc.sql("update wallet set balance_ngwee = -1 where id = :id").param("id", w.id()).update())
                .rootCause().hasMessageContaining("check constraint");
        assertThat(jdbc.sql("select balance_ngwee from wallet where id = :id").param("id", w.id())
                .query(Long.class).single()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("only the issuer may hold a negative balance, and it does: it holds minus all the e-money")
    void issuerHoldsTheNegative() {
        wallet(5_000);

        Long issuer = jdbc.sql("select balance_ngwee from wallet where type = 'ISSUER'").query(Long.class).single();

        assertThat(issuer).isNegative();
        assertThat(jdbc.sql("select sum(balance_ngwee) from wallet").query(Long.class).single())
                .as("and everything adds up to exactly zero").isZero();
    }

    @Test
    @DisplayName("there is exactly one issuer, and a second cannot be added even directly")
    void oneIssuer() {
        assertThatThrownBy(() -> jdbc.sql("insert into wallet (id, type, name, created_at) values (:id, 'ISSUER', 'Second', now())")
                .param("id", UUID.randomUUID()).update())
                .rootCause().hasMessageContaining("wallet_one_issuer");
    }

    @Test
    @DisplayName("a ledger line without its matching line is refused when the transaction commits (double entry)")
    void unbalancedLinesRefused() {
        Wallet w = wallet(1_000);
        Wallet other = wallet(0);
        UUID tx2 = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.sql("insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, created_at, completed_at) "
                            + "values (:id, 'TRANSFER', 'SUCCEEDED', :from, :to, 100, now(), now())")
                    .param("id", tx2).param("from", w.id()).param("to", other.id()).update();
            // Only the debit line: money would vanish.
            jdbc.sql("insert into ledger_line (transaction_id, wallet_id, amount_ngwee, balance_after_ngwee, created_at) "
                            + "values (:tx, :wallet, -100, 900, now())")
                    .param("tx", tx2).param("wallet", w.id()).update();
        })).rootCause().hasMessageContaining("is unbalanced");

        assertThat(jdbc.sql("select count(*) from transaction where id = :id").param("id", tx2).query(Long.class).single())
                .as("nothing was left behind").isZero();
    }

    @Test
    @DisplayName("a balanced pair of lines is accepted, so the rule is not simply refusing everything")
    void balancedLinesAccepted() {
        Wallet a = wallet(1_000);
        Wallet b = wallet(0);
        UUID tx = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            jdbc.sql("insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, created_at, completed_at) "
                            + "values (:id, 'TRANSFER', 'SUCCEEDED', :from, :to, 100, now(), now())")
                    .param("id", tx).param("from", a.id()).param("to", b.id()).update();
            jdbc.sql("insert into ledger_line (transaction_id, wallet_id, amount_ngwee, balance_after_ngwee, created_at) "
                            + "values (:tx, :w, -100, 900, now()), (:tx, :w2, 100, 100, now())")
                    .param("tx", tx).param("w", a.id()).param("w2", b.id()).update();
        });

        assertThat(jdbc.sql("select count(*) from ledger_line where transaction_id = :tx").param("tx", tx)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    @DisplayName("ledger lines are never updated, deleted or truncated, by anyone")
    void ledgerIsAppendOnly() {
        Wallet w = wallet(1_000);

        assertThatThrownBy(() -> jdbc.sql("update ledger_line set amount_ngwee = amount_ngwee + 1 where wallet_id = :w")
                .param("w", w.id()).update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from ledger_line where wallet_id = :w").param("w", w.id()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("truncate ledger_line").update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a transaction cannot be for zero or a negative amount, or from a wallet to itself, even directly")
    void transactionChecks() {
        Wallet a = wallet(1_000);
        Wallet b = wallet(0);
        String insert = "insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, amount_ngwee, created_at, completed_at) "
                + "values (:id, 'TRANSFER', 'SUCCEEDED', :from, :to, :amount, now(), now())";

        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("from", a.id()).param("to", b.id())
                .param("amount", 0L).update()).as("zero").rootCause().hasMessageContaining("check constraint");
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("from", a.id()).param("to", b.id())
                .param("amount", -5L).update()).as("negative").rootCause().hasMessageContaining("check constraint");
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("from", a.id()).param("to", a.id())
                .param("amount", 5L).update()).as("to itself").rootCause().hasMessageContaining("check constraint");
    }

    @Test
    @DisplayName("the transaction table records a failure's reason exactly when it failed")
    void failureReasonOnlyWhenFailed() {
        Wallet a = wallet(1_000);
        Wallet b = wallet(0);

        assertThatThrownBy(() -> jdbc.sql("insert into transaction (id, kind, status, from_wallet_id, to_wallet_id, "
                        + "amount_ngwee, created_at) values (:id, 'TRANSFER', 'FAILED', :f, :t, 5, now())")
                .param("id", UUID.randomUUID()).param("f", a.id()).param("t", b.id()).update())
                .as("failed without a reason").rootCause().hasMessageContaining("check constraint");
    }

    @Test
    @DisplayName("a fund always comes from the issuer")
    void fundComesFromTheIssuer() {
        Wallet w = wallet(0);

        Transaction fund = wallets.fund("k-" + UUID.randomUUID(), w.id(), 700, "x");

        assertThat(fund.fromWalletId()).isEqualTo(zm.msika.zamsim.wallet.Wallets.ISSUER_ID);
    }
}
