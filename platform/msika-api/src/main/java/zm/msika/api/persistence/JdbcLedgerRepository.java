package zm.msika.api.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.api.ledger.LedgerRows;
import zm.msika.core.common.ConflictException;
import zm.msika.core.ledger.LedgerRepository;
import zm.msika.core.ledger.NewEntry;
import zm.msika.ledger.LedgerEntry;

/**
 * Keeps ledger entries in Postgres. There is no update and no delete here, and the database would
 * refuse them anyway (see migration V6). Only the {@code RecordEntry} use case calls {@link #add}.
 */
@Repository
class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcClient jdbc;

    JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LedgerEntry add(NewEntry entry, LocalDate businessDate) {
        try {
            // ON CONFLICT DO NOTHING, not an error: a duplicate key would otherwise abort the whole transaction, and
            // RecordEntry could then no longer read the entry that won the race. Postgres waits here for a
            // competing insert of the same key to commit before deciding.
            Optional<Long> inserted = jdbc.sql("insert into ledger_entry (council_id, stall_id, kind, provenance, amount_ngwee, "
                            + "business_date, occurred_at, idempotency_key, reverses_entry_id) "
                            + "values (:councilId, :stallId, :kind, :provenance, :amount, :businessDate, "
                            + ":occurredAt, :key, :reverses) on conflict do nothing returning id")
                    .param("councilId", entry.councilId())
                    .param("stallId", entry.stallId())
                    .param("kind", entry.kind().name())
                    .param("provenance", entry.provenance().name())
                    .param("amount", entry.amount().minorUnits())
                    .param("businessDate", businessDate)
                    .param("occurredAt", OffsetDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC))
                    .param("key", entry.idempotencyKey())
                    .param("reverses", entry.reversesEntryId())
                    .query(Long.class)
                    .optional();
            if (inserted.isEmpty()) {
                // The unique (council, idempotency key) index, or the one-reversal-per-entry index.
                throw new ConflictException("an entry with idempotency key " + entry.idempotencyKey()
                        + " (or a reversal of the same entry) is already recorded");
            }
            return entry.toEntry(inserted.get(), businessDate);
        } catch (DuplicateKeyException e) {
            // Not expected (ON CONFLICT DO NOTHING handles duplicates), kept as a safety net.
            throw new ConflictException("an entry with idempotency key " + entry.idempotencyKey()
                    + " (or a reversal of the same entry) is already recorded");
        }
    }

    @Override
    public Optional<LedgerEntry> findByKey(UUID councilId, String idempotencyKey) {
        return jdbc.sql("select " + LedgerRows.COLUMNS
                        + " from ledger_entry where council_id = :councilId and idempotency_key = :key")
                .param("councilId", councilId)
                .param("key", idempotencyKey)
                .query((rs, row) -> LedgerRows.map(rs))
                .optional();
    }

    @Override
    public Optional<LedgerEntry> findById(UUID councilId, long id) {
        return jdbc.sql("select " + LedgerRows.COLUMNS + " from ledger_entry where council_id = :councilId and id = :id")
                .param("councilId", councilId)
                .param("id", id)
                .query((rs, row) -> LedgerRows.map(rs))
                .optional();
    }

    @Override
    public boolean isReversed(UUID councilId, long entryId) {
        return jdbc.sql("select count(*) from ledger_entry where council_id = :councilId and reverses_entry_id = :id")
                .param("councilId", councilId)
                .param("id", entryId)
                .query(Integer.class)
                .single() > 0;
    }

    @Override
    public List<LedgerEntry> entriesOfDay(UUID councilId, LocalDate businessDate) {
        return jdbc.sql("select " + LedgerRows.COLUMNS + " from ledger_entry "
                        + "where council_id = :councilId and business_date = :date order by id")
                .param("councilId", councilId)
                .param("date", businessDate)
                .query((rs, row) -> LedgerRows.map(rs))
                .list();
    }
}
