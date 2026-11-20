package zm.msika.core.ledger;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.stall.StallRepository;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;

/**
 * Use case: record a ledger entry. <strong>The only way an entry is ever written</strong> (AGENTS.md
 * rule 2). Nothing else calls {@link LedgerRepository#add}.
 *
 * <p>What it guarantees:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The same idempotency key with the same content records one entry and returns
 *       it again. The same key with different content is a conflict, never a silent overwrite.
 *   <li><b>Business date.</b> The date is the entry's date in the council's time zone, unless that day
 *       (or a later one) is already sealed, in which case the entry goes to the next open day. A sealed
 *       day never changes.
 *   <li><b>Reversals are real.</b> A reversal must cancel an existing entry exactly (same stall,
 *       provenance and opposite amount), and an entry can be reversed once. The original is untouched.
 * </ul>
 */
public final class RecordEntry {

    /** The result: the entry, and whether it was already there. */
    public record Recorded(LedgerEntry entry, boolean duplicate) {}

    private final CouncilRepository councils;
    private final LedgerRepository ledger;
    private final StallRepository stalls;
    private final SealRepository seals;
    private final LedgerLock lock;

    public RecordEntry(
            CouncilRepository councils,
            StallRepository stalls,
            LedgerRepository ledger,
            SealRepository seals,
            LedgerLock lock) {
        this.councils = Objects.requireNonNull(councils);
        this.stalls = Objects.requireNonNull(stalls);
        this.ledger = Objects.requireNonNull(ledger);
        this.seals = Objects.requireNonNull(seals);
        this.lock = Objects.requireNonNull(lock);
    }

    /**
     * @throws NotFoundException if the council, the stall, or the entry to reverse, does not exist
     * @throws ConflictException if the key was used for different content, or the reversal is not allowed
     */
    public Recorded execute(NewEntry request) {
        Council council = councils.find(request.councilId())
                .orElseThrow(() -> new NotFoundException("council not found"));
        lock.lockForRecording(request.councilId());
        stalls.find(request.councilId(), request.stallId()).orElseThrow(() -> new NotFoundException("stall not found"));

        Optional<LedgerEntry> existing = ledger.findByKey(request.councilId(), request.idempotencyKey());
        if (existing.isPresent()) {
            return repeat(request, existing.get());
        }

        if (request.kind() == EntryKind.REVERSAL) {
            checkReversal(request);
        }

        LocalDate businessDate = businessDate(council, request);
        try {
            return new Recorded(ledger.add(request, businessDate), false);
        } catch (ConflictException raced) {
            // Another request recorded this key between our check and our insert. If it is the same
            // thing, that is a harmless repeat; the unique index guarantees there is only one.
            return ledger.findByKey(request.councilId(), request.idempotencyKey())
                    .map(found -> repeat(request, found))
                    .orElseThrow(() -> raced);
        }
    }

    private Recorded repeat(NewEntry request, LedgerEntry existing) {
        if (!request.matches(existing)) {
            throw new ConflictException(
                    "idempotency key " + request.idempotencyKey() + " was already used for a different entry");
        }
        return new Recorded(existing, true);
    }

    /**
     * The entry's own day in the council's time zone, unless that day is not open: a day that is already
     * sealed (or earlier), or before the council existed (there is nothing to seal then). Such an entry goes to the
     * first open day, which is the day after the latest seal, or the council's creation day if it has none.
     */
    private LocalDate businessDate(Council council, NewEntry request) {
        LocalDate eventDay = request.occurredAt().atZone(council.timezone()).toLocalDate();
        LocalDate firstOpenDay = seals.latest(request.councilId())
                .map(latest -> latest.seal().businessDate().plusDays(1))
                .orElseGet(() -> council.createdAt().atZone(council.timezone()).toLocalDate());
        return eventDay.isBefore(firstOpenDay) ? firstOpenDay : eventDay;
    }

    private void checkReversal(NewEntry request) {
        long targetId = request.reversesEntryId();
        LedgerEntry target = ledger.findById(request.councilId(), targetId)
                .orElseThrow(() -> new NotFoundException("the entry to reverse was not found"));
        if (target.kind() == EntryKind.REVERSAL) {
            throw new ConflictException("a reversal cannot itself be reversed");
        }
        if (ledger.isReversed(request.councilId(), targetId)) {
            throw new ConflictException("entry " + targetId + " has already been reversed");
        }
        if (!target.stallId().equals(request.stallId())) {
            throw new ConflictException("a reversal must be for the same stall as the entry it cancels");
        }
        if (target.provenance() != request.provenance()) {
            throw new ConflictException("a reversal must have the same provenance as the entry it cancels");
        }
        if (!target.amount().negate().equals(request.amount())) {
            throw new ConflictException("a reversal must be exactly the opposite amount of the entry it cancels");
        }
    }
}
