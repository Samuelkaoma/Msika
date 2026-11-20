package zm.msika.api.tenancy;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import zm.msika.core.council.CouncilRepository;

/**
 * Tells Postgres which council the current transaction is working for, so row-level security can
 * hide every other council's rows (AGENTS.md rule 4, ARCHITECTURE.md section 5).
 *
 * <p>Every database access for council data must happen in a transaction on which {@link #use} (or
 * {@link #inCouncil}) has been called. The setting is local to the transaction, so it cannot leak to
 * the next request that reuses the same pooled connection.
 *
 * <p>Requests set it from the signed-in user's council (card M-09). Scheduled jobs that work across
 * councils use {@link #forEachCouncil}, which sets it for one council at a time. There is no way to
 * see all councils' rows at once.
 */
@Component
public class TenantContext {

    /** The Postgres setting the row-level security policies read. */
    static final String SETTING = "app.council_id";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final CouncilRepository councils;

    TenantContext(JdbcClient jdbc, TransactionTemplate transactions, CouncilRepository councils) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.councils = councils;
    }

    /**
     * Sets the council for the current transaction.
     *
     * @throws IllegalStateException if there is no transaction, because the setting would then last
     *     only for one statement and the next query would silently see nothing
     */
    public void use(UUID councilId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantContext.use needs an active transaction");
        }
        jdbc.sql("select set_config('" + SETTING + "', :councilId, true)")
                .param("councilId", councilId.toString())
                .query(String.class)
                .single();
    }

    /** Runs the work in its own transaction, as the given council. */
    public <T> T inCouncil(UUID councilId, Supplier<T> work) {
        return transactions.execute(status -> {
            use(councilId);
            return work.get();
        });
    }

    /** Like {@link #inCouncil} for work that returns nothing. */
    public void inCouncil(UUID councilId, Runnable work) {
        inCouncil(councilId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs a job once for every council, each in its own transaction with only that council visible.
     * If the job fails for one council, the exception is thrown and later councils are not run.
     */
    public void forEachCouncil(Consumer<UUID> job) {
        for (UUID councilId : councils.listIds()) {
            inCouncil(councilId, () -> job.accept(councilId));
        }
    }
}
