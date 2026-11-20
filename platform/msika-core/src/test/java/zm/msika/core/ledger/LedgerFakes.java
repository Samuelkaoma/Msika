package zm.msika.core.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.ledger.LedgerEntry;

/** In-memory stand-ins for the ledger ports. */
final class LedgerFakes {

    private LedgerFakes() {}

    static final class Ledger implements LedgerRepository {
        final List<LedgerEntry> entries = new ArrayList<>();
        private long nextId = 1;
        int addCalls;

        @Override
        public LedgerEntry add(NewEntry request, LocalDate businessDate) {
            addCalls++;
            if (findByKey(request.councilId(), request.idempotencyKey()).isPresent()) {
                throw new ConflictException("idempotency key already used");
            }
            LedgerEntry entry = request.toEntry(nextId++, businessDate);
            entries.add(entry);
            return entry;
        }

        @Override
        public Optional<LedgerEntry> findByKey(UUID councilId, String key) {
            return entries.stream()
                    .filter(e -> e.councilId().equals(councilId) && e.idempotencyKey().equals(key)).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findById(UUID councilId, long id) {
            return entries.stream().filter(e -> e.councilId().equals(councilId) && e.id() == id).findFirst();
        }

        @Override
        public boolean isReversed(UUID councilId, long entryId) {
            return entries.stream().anyMatch(e -> e.councilId().equals(councilId) && e.reverses().isPresent()
                    && e.reversesEntryId() == entryId);
        }

        @Override
        public List<LedgerEntry> entriesOfDay(UUID councilId, LocalDate businessDate) {
            return entries.stream()
                    .filter(e -> e.councilId().equals(councilId) && e.businessDate().equals(businessDate))
                    .sorted(Comparator.comparingLong(LedgerEntry::id))
                    .toList();
        }
    }

    static final class Seals implements SealRepository {
        final List<SealedDay> all = new ArrayList<>();

        @Override
        public void add(UUID councilId, SealedDay sealed) {
            all.add(sealed);
        }

        @Override
        public Optional<SealedDay> find(UUID councilId, LocalDate businessDate) {
            return all.stream().filter(s -> s.seal().councilId().equals(councilId)
                    && s.seal().businessDate().equals(businessDate)).findFirst();
        }

        @Override
        public Optional<SealedDay> latest(UUID councilId) {
            return list(councilId).stream().reduce((first, second) -> second);
        }

        @Override
        public List<SealedDay> list(UUID councilId) {
            return all.stream().filter(s -> s.seal().councilId().equals(councilId))
                    .sorted(Comparator.comparing(s -> s.seal().businessDate())).toList();
        }
    }

    static final class Lock implements LedgerLock {
        final List<String> calls = new ArrayList<>();

        @Override
        public void lockForRecording(UUID councilId) {
            calls.add("record");
        }

        @Override
        public void lockForClosing(UUID councilId) {
            calls.add("close");
        }
    }
}
