package zm.msika.core.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * A ledger entry that has been asked for but not recorded yet, so it has no id and no business date.
 * {@link RecordEntry} decides the business date, and the database assigns the id.
 *
 * <p>The same rules as {@link LedgerEntry} apply, and are checked here by building a throwaway one, so a
 * request that could never become a valid entry is refused before anything is looked up.
 *
 * @param councilId the council whose ledger this is
 * @param stallId the stall the entry is recorded against
 * @param kind what it records
 * @param provenance how sure we are
 * @param amount a positive amount, or the negative of the entry it reverses for a reversal; ZMW
 * @param occurredAt when the underlying event happened. Rounded down to the microsecond, which is all the
 *     database keeps, so what we compare and seal is exactly what is stored.
 * @param idempotencyKey a key unique within the council: the same input twice records one entry
 * @param reversesEntryId for a reversal, the id of the entry it cancels; otherwise {@code null}
 */
public record NewEntry(
        UUID councilId,
        UUID stallId,
        EntryKind kind,
        Provenance provenance,
        Money amount,
        Instant occurredAt,
        String idempotencyKey,
        Long reversesEntryId) {

    /** A placeholder id and date used only to run LedgerEntry's checks. */
    private static final long PLACEHOLDER_ID = Long.MAX_VALUE;

    public NewEntry {
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        // Throws InvalidInputException-style IllegalArgumentException if the entry could never be valid.
        asEntry(PLACEHOLDER_ID, councilId, stallId, kind, provenance, amount, java.time.LocalDate.EPOCH, occurredAt,
                idempotencyKey, reversesEntryId);
    }

    /** The recorded form of this request, with its id and business date. */
    public LedgerEntry toEntry(long id, java.time.LocalDate businessDate) {
        return asEntry(id, councilId, stallId, kind, provenance, amount, businessDate, occurredAt, idempotencyKey,
                reversesEntryId);
    }

    private static LedgerEntry asEntry(
            long id, UUID councilId, UUID stallId, EntryKind kind, Provenance provenance, Money amount,
            java.time.LocalDate businessDate, Instant occurredAt, String idempotencyKey, Long reversesEntryId) {
        return new LedgerEntry(id, councilId, stallId, kind, provenance, amount, businessDate, occurredAt,
                idempotencyKey, reversesEntryId);
    }

    /** True if an already recorded entry is exactly what this request asks for (a harmless repeat). */
    public boolean matches(LedgerEntry recorded) {
        return recorded.councilId().equals(councilId)
                && recorded.stallId().equals(stallId)
                && recorded.kind() == kind
                && recorded.provenance() == provenance
                && recorded.amount().equals(amount)
                && recorded.occurredAt().equals(occurredAt)
                && Objects.equals(recorded.reversesEntryId(), reversesEntryId);
    }
}
