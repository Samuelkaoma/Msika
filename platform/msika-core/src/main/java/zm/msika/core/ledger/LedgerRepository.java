package zm.msika.core.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zm.msika.ledger.LedgerEntry;

/**
 * Where ledger entries are kept. Implemented in msika-api. There is deliberately no update and no delete:
 * the ledger only grows (AGENTS.md rule 2), and only {@link RecordEntry} calls {@link #add}.
 */
public interface LedgerRepository {

    /**
     * Records the entry and returns it with the id the database gave it.
     *
     * @throws zm.msika.core.common.ConflictException if the idempotency key is already used
     */
    LedgerEntry add(NewEntry entry, LocalDate businessDate);

    Optional<LedgerEntry> findByKey(UUID councilId, String idempotencyKey);

    Optional<LedgerEntry> findById(UUID councilId, long id);

    /** True if some reversal already points at this entry. */
    boolean isReversed(UUID councilId, long entryId);

    /** The entries of one business day, in id order: the order they are sealed in. */
    List<LedgerEntry> entriesOfDay(UUID councilId, LocalDate businessDate);
}
