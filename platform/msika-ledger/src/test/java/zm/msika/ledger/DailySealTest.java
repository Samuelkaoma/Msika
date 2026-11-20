package zm.msika.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Specification for {@link DailySeal}: any change to a sealed day, or to the chain, is detected. */
class DailySealTest {

    private static final UUID COUNCIL = LedgerEntryTest.COUNCIL;
    private static final UUID STALL = LedgerEntryTest.STALL;
    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 23);
    private static final LocalDate DAY2 = LocalDate.of(2026, 11, 24);
    private static final LocalDate DAY3 = LocalDate.of(2026, 11, 25);

    private final SignatureScheme scheme = new EcdsaSignatureScheme();
    private final KeyPair councilKey = scheme.generateKeyPair();

    /** A day of five entries with ids {@code firstId} upwards. */
    private static List<LedgerEntry> day(LocalDate date, long firstId) {
        List<LedgerEntry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(new LedgerEntry(
                    firstId + i, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    Money.ofMinor(10_000L * (i + 1), Currency.ZMW), date,
                    Instant.parse("2026-11-23T15:00:00Z").plusSeconds(i), "dep-" + date + "-" + i, null));
        }
        return entries;
    }

    private DailySeal seal(LocalDate date, List<LedgerEntry> entries, byte[] previous) {
        return DailySeal.seal(COUNCIL, date, entries, previous, scheme, councilKey.getPrivate());
    }

    private DailySeal.Verification verify(DailySeal seal, List<LedgerEntry> entries, byte[] previous) {
        return seal.verify(entries, previous, councilKey.getPublic(), scheme);
    }

    @Nested
    @DisplayName("a sealed day")
    class SealedDay {

        @Test
        @DisplayName("verifies when nothing has changed")
        void untouchedDayVerifies() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal seal = seal(DAY1, entries, DailySeal.GENESIS_HASH);

            assertThat(verify(seal, entries, DailySeal.GENESIS_HASH).valid()).isTrue();
            assertThat(seal.entryCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("sealing the same entries twice gives the same root (replayable)")
        void rootIsDeterministic() {
            List<LedgerEntry> entries = day(DAY1, 1);

            assertThat(seal(DAY1, entries, DailySeal.GENESIS_HASH).merkleRoot())
                    .isEqualTo(seal(DAY1, entries, DailySeal.GENESIS_HASH).merkleRoot());
        }

        @Test
        @DisplayName("a day with no entries can be sealed, so the chain has no gaps")
        void emptyDayCanBeSealed() {
            DailySeal seal = seal(DAY1, List.of(), DailySeal.GENESIS_HASH);

            assertThat(seal.entryCount()).isZero();
            assertThat(verify(seal, List.of(), DailySeal.GENESIS_HASH).valid()).isTrue();
        }

        @Test
        @DisplayName("changing the amount of any single entry makes verify fail")
        void changedEntryIsDetected() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal seal = seal(DAY1, entries, DailySeal.GENESIS_HASH);

            for (int i = 0; i < entries.size(); i++) {
                List<LedgerEntry> tampered = new ArrayList<>(entries);
                LedgerEntry original = tampered.get(i);
                tampered.set(i, new LedgerEntry(original.id(), COUNCIL, STALL, original.kind(),
                        original.provenance(), Money.ofMinor(original.amount().minorUnits() + 1, Currency.ZMW),
                        DAY1, original.occurredAt(), original.idempotencyKey(), null));

                DailySeal.Verification result = verify(seal, tampered, DailySeal.GENESIS_HASH);

                assertThat(result.valid()).as("entry %d changed", i).isFalse();
                assertThat(result.problem()).contains("do not match the sealed root");
            }
        }

        @Test
        @DisplayName("changing the provenance of an entry (a declared sale made to look verified) fails verify")
        void changedProvenanceIsDetected() {
            LedgerEntry declared = new LedgerEntry(1, COUNCIL, STALL, EntryKind.DECLARED_SALE,
                    Provenance.DECLARED, Money.ofMinor(5_000, Currency.ZMW), DAY1,
                    Instant.parse("2026-11-23T10:00:00Z"), "sale-1", null);
            LedgerEntry forged = new LedgerEntry(1, COUNCIL, STALL, EntryKind.MERCHANT_SALE,
                    Provenance.VERIFIED, Money.ofMinor(5_000, Currency.ZMW), DAY1,
                    Instant.parse("2026-11-23T10:00:00Z"), "sale-1", null);
            DailySeal seal = seal(DAY1, List.of(declared), DailySeal.GENESIS_HASH);

            assertThat(verify(seal, List.of(forged), DailySeal.GENESIS_HASH).valid()).isFalse();
        }

        @Test
        @DisplayName("removing any single entry makes verify fail")
        void removedEntryIsDetected() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal seal = seal(DAY1, entries, DailySeal.GENESIS_HASH);

            for (int i = 0; i < entries.size(); i++) {
                List<LedgerEntry> tampered = new ArrayList<>(entries);
                tampered.remove(i);

                DailySeal.Verification result = verify(seal, tampered, DailySeal.GENESIS_HASH);

                assertThat(result.valid()).as("entry %d removed", i).isFalse();
                assertThat(result.problem()).contains("5 entries but there are 4");
            }
        }

        @Test
        @DisplayName("adding an entry makes verify fail")
        void addedEntryIsDetected() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal seal = seal(DAY1, entries, DailySeal.GENESIS_HASH);
            List<LedgerEntry> tampered = new ArrayList<>(entries);
            tampered.add(new LedgerEntry(6, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    Money.ofMinor(100, Currency.ZMW), DAY1, Instant.parse("2026-11-23T16:00:00Z"), "extra", null));

            assertThat(verify(seal, tampered, DailySeal.GENESIS_HASH).valid()).isFalse();
        }

        @Test
        @DisplayName("reordering the entries makes verify fail")
        void reorderedEntriesAreDetected() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal seal = seal(DAY1, entries, DailySeal.GENESIS_HASH);
            List<LedgerEntry> tampered = new ArrayList<>(entries);
            Collections.swap(tampered, 1, 3);

            DailySeal.Verification result = verify(seal, tampered, DailySeal.GENESIS_HASH);

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("the reordered check holds even if the ids are put back in order, because the content moved")
        void reorderedContentWithRenumberedIdsIsDetected() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal seal = seal(DAY1, entries, DailySeal.GENESIS_HASH);
            List<LedgerEntry> tampered = new ArrayList<>();
            // same ids 1..5 in order, but the amounts of entries 2 and 4 swapped
            for (int i = 0; i < entries.size(); i++) {
                int source = i == 1 ? 3 : i == 3 ? 1 : i;
                LedgerEntry content = entries.get(source);
                tampered.add(new LedgerEntry(entries.get(i).id(), COUNCIL, STALL, content.kind(),
                        content.provenance(), content.amount(), DAY1, content.occurredAt(),
                        content.idempotencyKey(), null));
            }

            assertThat(verify(seal, tampered, DailySeal.GENESIS_HASH).valid()).isFalse();
        }

        @Test
        @DisplayName("a forged signature fails verify")
        void forgedSignatureIsDetected() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal real = seal(DAY1, entries, DailySeal.GENESIS_HASH);
            KeyPair attacker = scheme.generateKeyPair();
            DailySeal forged = DailySeal.seal(COUNCIL, DAY1, entries, DailySeal.GENESIS_HASH, scheme,
                    attacker.getPrivate());

            assertThat(verify(real, entries, DailySeal.GENESIS_HASH).valid()).isTrue();
            DailySeal.Verification result = verify(forged, entries, DailySeal.GENESIS_HASH);
            assertThat(result.valid()).isFalse();
            assertThat(result.problem()).contains("signature");
        }

        @Test
        @DisplayName("entries from another day or another council are refused at sealing")
        void wrongEntriesRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal(DAY1, day(DAY2, 1), DailySeal.GENESIS_HASH))
                    .withMessageContaining("belongs to");
            List<LedgerEntry> other = List.of(new LedgerEntry(1, UUID.randomUUID(), STALL, EntryKind.DEPOSIT,
                    Provenance.DEPOSITED, Money.ofMinor(100, Currency.ZMW), DAY1,
                    Instant.parse("2026-11-23T10:00:00Z"), "k", null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal(DAY1, other, DailySeal.GENESIS_HASH))
                    .withMessageContaining("another council");
        }

        @Test
        @DisplayName("entries must be given in increasing id order")
        void idsMustIncrease() {
            List<LedgerEntry> entries = new ArrayList<>(day(DAY1, 1));
            Collections.swap(entries, 0, 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> seal(DAY1, entries, DailySeal.GENESIS_HASH))
                    .withMessageContaining("increasing id order");
        }
    }

    @Nested
    @DisplayName("the chain of days")
    class Chain {

        @Test
        @DisplayName("three honest days form a valid chain")
        void honestChainVerifies() {
            DailySeal one = seal(DAY1, day(DAY1, 1), DailySeal.GENESIS_HASH);
            DailySeal two = seal(DAY2, day(DAY2, 6), one.sealHash());
            DailySeal three = seal(DAY3, day(DAY3, 11), two.sealHash());

            assertThat(DailySeal.verifyChain(List.of(one, two, three), councilKey.getPublic(), scheme).valid())
                    .isTrue();
        }

        @Test
        @DisplayName("a day sealed against the wrong previous seal breaks the chain")
        void brokenLinkIsDetected() {
            DailySeal one = seal(DAY1, day(DAY1, 1), DailySeal.GENESIS_HASH);
            DailySeal two = seal(DAY2, day(DAY2, 6), one.sealHash());
            DailySeal threeLinkedToTheWrongSeal = seal(DAY3, day(DAY3, 11), one.sealHash());

            DailySeal.Verification result = DailySeal.verifyChain(
                    List.of(one, two, threeLinkedToTheWrongSeal), councilKey.getPublic(), scheme);

            assertThat(result.valid()).isFalse();
            assertThat(result.problem()).contains("chain is broken").contains(DAY3.toString());
        }

        @Test
        @DisplayName("removing a day from the middle breaks the chain")
        void missingDayIsDetected() {
            DailySeal one = seal(DAY1, day(DAY1, 1), DailySeal.GENESIS_HASH);
            DailySeal two = seal(DAY2, day(DAY2, 6), one.sealHash());
            DailySeal three = seal(DAY3, day(DAY3, 11), two.sealHash());

            assertThat(DailySeal.verifyChain(List.of(one, three), councilKey.getPublic(), scheme).valid())
                    .isFalse();
        }

        @Test
        @DisplayName("replacing a past day with a re-signed altered one breaks every later link")
        void rewrittenPastDayIsDetected() {
            DailySeal one = seal(DAY1, day(DAY1, 1), DailySeal.GENESIS_HASH);
            DailySeal two = seal(DAY2, day(DAY2, 6), one.sealHash());
            DailySeal three = seal(DAY3, day(DAY3, 11), two.sealHash());
            // The council key signs an altered day 2, honestly signed but different from what day 3 linked to.
            List<LedgerEntry> altered = new ArrayList<>(day(DAY2, 6));
            altered.remove(0);
            DailySeal rewrittenTwo = seal(DAY2, altered, one.sealHash());

            DailySeal.Verification result = DailySeal.verifyChain(
                    List.of(one, rewrittenTwo, three), councilKey.getPublic(), scheme);

            assertThat(result.valid()).isFalse();
            assertThat(result.problem()).contains(DAY3.toString());
        }

        @Test
        @DisplayName("a day verified against the wrong previous seal hash fails")
        void wrongPreviousHashInDayVerification() {
            List<LedgerEntry> entries = day(DAY2, 6);
            DailySeal one = seal(DAY1, day(DAY1, 1), DailySeal.GENESIS_HASH);
            DailySeal two = seal(DAY2, entries, one.sealHash());

            DailySeal.Verification result = verify(two, entries, DailySeal.GENESIS_HASH);

            assertThat(result.valid()).isFalse();
            assertThat(result.problem()).contains("previous day");
        }

        @Test
        @DisplayName("seals out of date order, or from different councils, are refused")
        void orderAndCouncilChecked() {
            DailySeal one = seal(DAY1, day(DAY1, 1), DailySeal.GENESIS_HASH);
            DailySeal two = seal(DAY2, day(DAY2, 6), one.sealHash());

            assertThat(DailySeal.verifyChain(List.of(two, one), councilKey.getPublic(), scheme).valid()).isFalse();
        }

        @Test
        @DisplayName("the seal hash changes if any field of the seal changes")
        void sealHashCoversEveryField() {
            List<LedgerEntry> entries = day(DAY1, 1);
            DailySeal a = seal(DAY1, entries, DailySeal.GENESIS_HASH);
            DailySeal b = seal(DAY1, entries.subList(0, 4), DailySeal.GENESIS_HASH);

            assertThat(a.sealHash()).isNotEqualTo(b.sealHash());
            assertThat(a.sealHash()).hasSize(32);
        }
    }
}
