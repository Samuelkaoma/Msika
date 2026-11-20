package zm.msika.api.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zm.msika.core.ledger.LedgerLock;

/**
 * Postgres advisory locks that last until the transaction ends. Recording takes the shared kind, day
 * close the exclusive kind (see {@link LedgerLock}). The key is a hash of the council id, so councils
 * never wait for each other.
 */
@Component
class PostgresLedgerLock implements LedgerLock {

    private final JdbcClient jdbc;

    PostgresLedgerLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockForRecording(UUID councilId) {
        requireTransaction();
        jdbc.sql("select pg_advisory_xact_lock_shared(hashtextextended(:council, 0))")
                .param("council", councilId.toString()).query().singleRow();
    }

    @Override
    public void lockForClosing(UUID councilId) {
        requireTransaction();
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:council, 0))")
                .param("council", councilId.toString()).query().singleRow();
    }

    /** A transaction-level lock outside a transaction is released at once, which would protect nothing. */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the ledger lock needs an active transaction");
        }
    }
}
