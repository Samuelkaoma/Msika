package zm.msika.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallStatus;
import zm.msika.ledger.Currency;
import zm.msika.ledger.DailySeal;
import zm.msika.ledger.EcdsaSignatureScheme;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;
import zm.msika.ledger.SignatureScheme;

/** Specification for recording entries and closing days, against in-memory repositories. */
class RecordEntryAndDayCloseTest {

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    private final Fakes.Councils councils = new Fakes.Councils();
    private final LedgerFakes.Ledger ledger = new LedgerFakes.Ledger();
    private final Fakes.Stalls stalls = new Fakes.Stalls();
    private final LedgerFakes.Seals seals = new LedgerFakes.Seals();
    private final LedgerFakes.Lock lock = new LedgerFakes.Lock();
    private final SignatureScheme scheme = new EcdsaSignatureScheme();
    private final KeyPair key = scheme.generateKeyPair();

    private final UUID stall = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID otherStall = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private Council council;
    private Clock clock;
    private RecordEntry record;
    private DayClose close;

    /** Builds the use cases with a clock that says it is 2026-11-25 at 21:00 in Lusaka (19:00 UTC). */
    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-25T19:00:00Z"), ZoneOffset.UTC);
        council = new Council(UUID.randomUUID(), "Lusaka City Council", LUSAKA, Instant.parse("2026-11-20T08:00:00Z"));
        councils.add(council);
        UUID market = UUID.randomUUID();
        stalls.addAll(List.of(
                new Stall(stall, council.id(), market, "SOW-B-1", "STANDARD", StallStatus.VACANT, "t".repeat(22)),
                new Stall(otherStall, council.id(), market, "SOW-B-2", "STANDARD", StallStatus.VACANT, "u".repeat(22))));
        record = new RecordEntry(councils, stalls, ledger, seals, lock);
        close = new DayClose(councils, ledger, seals, lock, scheme, key.getPrivate(), "test-key-1", clock);
    }

    private static Money kwacha(long ngwee) {
        return Money.ofMinor(ngwee, Currency.ZMW);
    }

    private NewEntry deposit(String keyName, long ngwee, String at) {
        return new NewEntry(council.id(), stall, EntryKind.DEPOSIT, Provenance.DEPOSITED, kwacha(ngwee),
                Instant.parse(at), keyName, null);
    }

    @Nested
    @DisplayName("recording is idempotent")
    class Idempotency {

        @Test
        @DisplayName("the same key and content twice records one entry and returns it again")
        void sameKeyTwice() {
            NewEntry request = deposit("dep-1", 45_000, "2026-11-24T10:00:00Z");

            RecordEntry.Recorded first = record.execute(request);
            RecordEntry.Recorded second = record.execute(request);

            assertThat(first.duplicate()).isFalse();
            assertThat(second.duplicate()).isTrue();
            assertThat(second.entry()).isEqualTo(first.entry());
            assertThat(ledger.entries).hasSize(1);
            assertThat(ledger.addCalls).as("the second call never wrote").isEqualTo(1);
        }

        @Test
        @DisplayName("the same key with a different amount is a conflict, and changes nothing")
        void sameKeyDifferentContent() {
            record.execute(deposit("dep-1", 45_000, "2026-11-24T10:00:00Z"));

            assertThatThrownBy(() -> record.execute(deposit("dep-1", 45_001, "2026-11-24T10:00:00Z")))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("dep-1");
            assertThat(ledger.entries).hasSize(1);
            assertThat(ledger.entries.get(0).amount().minorUnits()).isEqualTo(45_000);
        }

        @Test
        @DisplayName("a time with nanoseconds still matches itself after it is stored to the microsecond")
        void nanosecondsAreRoundedLikeTheDatabase() {
            NewEntry request = deposit("dep-1", 100, "2026-11-24T10:00:00.123456789Z");

            record.execute(request);

            assertThat(request.occurredAt()).isEqualTo(Instant.parse("2026-11-24T10:00:00.123456Z"));
            assertThat(record.execute(request).duplicate()).isTrue();
        }

        @Test
        @DisplayName("a key may be reused in another council")
        void keysArePerCouncil() {
            Council other = new Council(UUID.randomUUID(), "Kitwe City Council", LUSAKA, Instant.parse("2026-11-20T08:00:00Z"));
            councils.add(other);
            UUID stallOfOther = UUID.randomUUID();
            stalls.addAll(List.of(new Stall(stallOfOther, other.id(), UUID.randomUUID(), "KIT-A-1", "STANDARD",
                    StallStatus.VACANT, "v".repeat(22))));
            record.execute(deposit("dep-1", 100, "2026-11-24T10:00:00Z"));

            RecordEntry.Recorded elsewhere = record.execute(new NewEntry(other.id(), stallOfOther, EntryKind.DEPOSIT,
                    Provenance.DEPOSITED, kwacha(100), Instant.parse("2026-11-24T10:00:00Z"), "dep-1", null));

            assertThat(elsewhere.duplicate()).isFalse();
        }

        @Test
        @DisplayName("an unknown council is not found, and an impossible entry never gets as far as the ledger")
        void refusals() {
            assertThatThrownBy(() -> record.execute(new NewEntry(UUID.randomUUID(), stall, EntryKind.DEPOSIT,
                    Provenance.DEPOSITED, kwacha(100), Instant.parse("2026-11-24T10:00:00Z"), "k", null)))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> new NewEntry(council.id(), stall, EntryKind.DECLARED_SALE, Provenance.VERIFIED,
                    kwacha(100), Instant.parse("2026-11-24T10:00:00Z"), "k", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(ledger.entries).isEmpty();
        }

        @Test
        @DisplayName("recording takes the shared lock before it looks at anything")
        void takesTheSharedLock() {
            record.execute(deposit("dep-1", 100, "2026-11-24T10:00:00Z"));

            assertThat(lock.calls).containsExactly("record");
        }
    }

    @Nested
    @DisplayName("the business date")
    class BusinessDate {

        @Test
        @DisplayName("is the entry's own date in the council's time zone")
        void ownDate() {
            LedgerEntry entry = record.execute(deposit("a", 100, "2026-11-24T10:00:00Z")).entry();

            assertThat(entry.businessDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        }

        @Test
        @DisplayName("uses Lusaka time, so 23:30 UTC is already the next day there")
        void timeZoneBoundary() {
            LedgerEntry beforeMidnight = record.execute(deposit("a", 100, "2026-11-24T21:59:59Z")).entry();
            LedgerEntry afterMidnight = record.execute(deposit("b", 100, "2026-11-24T22:00:00Z")).entry();

            assertThat(beforeMidnight.businessDate()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(afterMidnight.businessDate()).as("22:00 UTC is 00:00 in Lusaka").isEqualTo(LocalDate.of(2026, 11, 25));
        }

        @Test
        @DisplayName("an entry for a day that is already sealed goes to the next open day")
        void afterTheDayIsSealed() {
            close.execute(council.id(), LocalDate.of(2026, 11, 24));

            LedgerEntry late = record.execute(deposit("late", 100, "2026-11-24T19:30:00Z")).entry();

            assertThat(late.businessDate()).isEqualTo(LocalDate.of(2026, 11, 25));
            assertThat(late.occurredAt()).as("what really happened is kept").isEqualTo(Instant.parse("2026-11-24T19:30:00Z"));
        }

        @Test
        @DisplayName("an entry for a day well before the last sealed day also goes to the next open day")
        void muchLater() {
            close.execute(council.id(), LocalDate.of(2026, 11, 24));

            LedgerEntry old = record.execute(deposit("old", 100, "2026-11-21T10:00:00Z")).entry();

            assertThat(old.businessDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        }

        @Test
        @DisplayName("an entry from before the council existed lands on the council's first day, so it is always sealed")
        void beforeTheCouncilExisted() {
            LedgerEntry early = record.execute(deposit("early", 100, "2026-11-10T10:00:00Z")).entry();

            assertThat(early.businessDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        }

        @Test
        @DisplayName("an entry for a day after the last sealed day keeps its own date")
        void afterTheSealedDays() {
            close.execute(council.id(), LocalDate.of(2026, 11, 23));

            LedgerEntry entry = record.execute(deposit("a", 100, "2026-11-25T08:00:00Z")).entry();

            assertThat(entry.businessDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        }
    }

    @Nested
    @DisplayName("reversals")
    class Reversals {

        private LedgerEntry original() {
            return record.execute(deposit("dep-1", 45_000, "2026-11-24T10:00:00Z")).entry();
        }

        private NewEntry reversalOf(LedgerEntry target, String keyName) {
            return new NewEntry(target.councilId(), target.stallId(), EntryKind.REVERSAL, target.provenance(),
                    target.amount().negate(), Instant.parse("2026-11-24T11:00:00Z"), keyName, target.id());
        }

        @Test
        @DisplayName("an exact reversal is recorded, and the original is left alone")
        void exactReversal() {
            LedgerEntry original = original();

            LedgerEntry reversal = record.execute(reversalOf(original, "rev-1")).entry();

            assertThat(reversal.kind()).isEqualTo(EntryKind.REVERSAL);
            assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
            assertThat(ledger.findById(council.id(), original.id()).orElseThrow()).isEqualTo(original);
            assertThat(ledger.entries).hasSize(2);
        }

        @Test
        @DisplayName("an entry can be reversed only once")
        void onlyOnce() {
            LedgerEntry original = original();
            record.execute(reversalOf(original, "rev-1"));

            assertThatThrownBy(() -> record.execute(reversalOf(original, "rev-2")))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("already been reversed");
        }

        @Test
        @DisplayName("a reversal of an entry that does not exist is not found")
        void unknownTarget() {
            assertThatThrownBy(() -> record.execute(new NewEntry(council.id(), stall, EntryKind.REVERSAL,
                    Provenance.DEPOSITED, kwacha(-100), Instant.parse("2026-11-24T11:00:00Z"), "rev", 999L)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a reversal must match the original's stall, provenance and amount exactly")
        void mustMatch() {
            LedgerEntry original = original();

            assertThatThrownBy(() -> record.execute(new NewEntry(council.id(), otherStall, EntryKind.REVERSAL,
                    original.provenance(), original.amount().negate(), Instant.parse("2026-11-24T11:00:00Z"), "r1", original.id())))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("same stall");
            assertThatThrownBy(() -> record.execute(new NewEntry(council.id(), stall, EntryKind.REVERSAL,
                    Provenance.DEPOSITED, kwacha(-44_999), Instant.parse("2026-11-24T11:00:00Z"), "r2", original.id())))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("opposite amount");
            assertThat(ledger.entries).hasSize(1);
        }

        @Test
        @DisplayName("a reversal cannot be reversed")
        void noReversalOfReversal() {
            LedgerEntry original = original();
            LedgerEntry reversal = record.execute(reversalOf(original, "rev-1")).entry();

            assertThatThrownBy(() -> record.execute(new NewEntry(council.id(), stall, EntryKind.REVERSAL,
                    Provenance.DEPOSITED, kwacha(-45_000), Instant.parse("2026-11-24T12:00:00Z"), "rev-2", reversal.id())))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("cannot itself be reversed");
        }

        @Test
        @DisplayName("a reversal made after the day is sealed lands on the next day, and the sealed day is untouched")
        void reversalAfterSealing() {
            LedgerEntry original = original();
            List<SealedDay> sealed = close.execute(council.id(), LocalDate.of(2026, 11, 24));
            byte[] rootBefore = sealed.get(sealed.size() - 1).seal().merkleRoot();

            LedgerEntry reversal = record.execute(reversalOf(original, "rev-1")).entry();

            assertThat(reversal.businessDate()).isEqualTo(LocalDate.of(2026, 11, 25));
            assertThat(seals.find(council.id(), LocalDate.of(2026, 11, 24)).orElseThrow().seal().merkleRoot())
                    .isEqualTo(rootBefore);
        }
    }

    @Nested
    @DisplayName("closing days")
    class Closing {

        private DailySeal sealOf(String date) {
            return seals.find(council.id(), LocalDate.parse(date)).orElseThrow().seal();
        }

        @Test
        @DisplayName("seals the day's entries, and the seal verifies against them")
        void sealsTheDay() {
            record.execute(deposit("a", 45_000, "2026-11-24T10:00:00Z"));
            record.execute(deposit("b", 12_000, "2026-11-24T11:00:00Z"));
            record.execute(deposit("c", 99_900, "2026-11-23T11:00:00Z"));

            close.execute(council.id(), LocalDate.of(2026, 11, 24));

            DailySeal seal = sealOf("2026-11-24");
            assertThat(seal.entryCount()).isEqualTo(2);
            byte[] previous = sealOf("2026-11-23").sealHash();
            assertThat(seal.verify(ledger.entriesOfDay(council.id(), LocalDate.of(2026, 11, 24)), previous,
                    key.getPublic(), scheme).valid()).isTrue();
        }

        @Test
        @DisplayName("running it twice seals nothing the second time and leaves the first seals as they were")
        void safeToRunTwice() {
            record.execute(deposit("a", 45_000, "2026-11-24T10:00:00Z"));
            List<SealedDay> first = close.execute(council.id(), LocalDate.of(2026, 11, 24));
            int sealsAfterFirst = seals.all.size();
            byte[] signatureBefore = sealOf("2026-11-24").signature();

            List<SealedDay> second = close.execute(council.id(), LocalDate.of(2026, 11, 24));

            assertThat(first).isNotEmpty();
            assertThat(second).isEmpty();
            assertThat(seals.all).hasSize(sealsAfterFirst);
            assertThat(sealOf("2026-11-24").signature()).isEqualTo(signatureBefore);
        }

        @Test
        @DisplayName("closing a day also closes every earlier day that was missed, oldest first, with no gap")
        void catchesUpMissedDays() {
            List<SealedDay> sealed = close.execute(council.id(), LocalDate.of(2026, 11, 24));

            assertThat(sealed).extracting(s -> s.seal().businessDate().toString())
                    .containsExactly("2026-11-20", "2026-11-21", "2026-11-22", "2026-11-23", "2026-11-24");
            assertThat(sealed.get(0).seal().previousSealHash()).as("the first seal chains to genesis")
                    .isEqualTo(DailySeal.GENESIS_HASH);
            assertThat(DailySeal.verifyChain(seals.list(council.id()).stream().map(SealedDay::seal).toList(),
                    key.getPublic(), scheme).valid()).isTrue();
        }

        @Test
        @DisplayName("a day with no entries is sealed too")
        void emptyDays() {
            close.execute(council.id(), LocalDate.of(2026, 11, 21));

            assertThat(sealOf("2026-11-21").entryCount()).isZero();
        }

        @Test
        @DisplayName("today can be closed (the operator's button), and the next day carries on from it")
        void closeToday() {
            close.execute(council.id(), LocalDate.of(2026, 11, 25));
            LedgerEntry after = record.execute(deposit("after", 100, "2026-11-25T19:30:00Z")).entry();

            assertThat(after.businessDate()).isEqualTo(LocalDate.of(2026, 11, 26));
        }

        @Test
        @DisplayName("a day that has not started yet cannot be closed")
        void futureDay() {
            assertThatThrownBy(() -> close.execute(council.id(), LocalDate.of(2026, 11, 26)))
                    .isInstanceOf(InvalidInputException.class).hasMessageContaining("has not started");
            assertThat(seals.all).isEmpty();
        }

        @Test
        @DisplayName("an unknown council is not found")
        void unknownCouncil() {
            assertThatThrownBy(() -> close.execute(UUID.randomUUID(), LocalDate.of(2026, 11, 24)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("closing takes the exclusive lock, and records the key that signed")
        void locksAndKeyId() {
            close.execute(council.id(), LocalDate.of(2026, 11, 21));

            assertThat(lock.calls).containsExactly("close");
            assertThat(seals.all).allSatisfy(s -> assertThat(s.keyId()).isEqualTo("test-key-1"));
        }

        @Test
        @DisplayName("an entry changed after sealing makes the seal fail to verify (the guarantee the whole design rests on)")
        void tamperingIsDetected() {
            LedgerEntry entry = record.execute(deposit("a", 45_000, "2026-11-24T10:00:00Z")).entry();
            close.execute(council.id(), LocalDate.of(2026, 11, 24));
            LedgerEntry tampered = new LedgerEntry(entry.id(), entry.councilId(), entry.stallId(), entry.kind(),
                    entry.provenance(), kwacha(45_001), entry.businessDate(), entry.occurredAt(),
                    entry.idempotencyKey(), null);

            assertThat(sealOf("2026-11-24").verify(List.of(tampered), sealOf("2026-11-23").sealHash(), key.getPublic(),
                    scheme).valid()).isFalse();
        }
    }
}
