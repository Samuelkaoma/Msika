package zm.msika.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Specification for {@link LedgerEntry}: an invalid entry cannot be constructed. */
class LedgerEntryTest {

    static final UUID COUNCIL = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID STALL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final LocalDate DAY = LocalDate.of(2026, 11, 23);
    static final Instant WHEN = Instant.parse("2026-11-23T15:42:10Z");

    static Money kwacha(long ngwee) {
        return Money.ofMinor(ngwee, Currency.ZMW);
    }

    static LedgerEntry deposit(long id, long ngwee, String key) {
        return new LedgerEntry(
                id, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED, kwacha(ngwee), DAY, WHEN, key, null);
    }

    @Nested
    @DisplayName("amounts")
    class Amounts {

        @Test
        @DisplayName("a deposit of K450.00 is 45000 ngwee")
        void storedAsWholeNgwee() {
            assertThat(deposit(1, 45_000, "dep-1").amount().minorUnits()).isEqualTo(45_000L);
        }

        @Test
        @DisplayName("a normal entry must have a positive amount")
        void positiveAmountRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> deposit(1, 0, "dep-1"))
                    .withMessageContaining("positive");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> deposit(1, -100, "dep-1"))
                    .withMessageContaining("positive");
        }

        @Test
        @DisplayName("only kwacha are recorded")
        void onlyZmw() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            1, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                            Money.ofMinor(100, Currency.USD), DAY, WHEN, "k", null))
                    .withMessageContaining("ZMW");
        }
    }

    @Nested
    @DisplayName("provenance is tied to the kind (rule 3)")
    class ProvenanceRules {

        @Test
        @DisplayName("a declared sale can only be DECLARED, never VERIFIED")
        void declaredCannotBePromotedToVerified() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            1, COUNCIL, STALL, EntryKind.DECLARED_SALE, Provenance.VERIFIED,
                            kwacha(12_000), DAY, WHEN, "sale-1", null))
                    .withMessageContaining("DECLARED");
        }

        @Test
        @DisplayName("a deposit can only be DEPOSITED")
        void depositMustBeDeposited() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            1, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.VERIFIED,
                            kwacha(100), DAY, WHEN, "d", null))
                    .withMessageContaining("DEPOSITED");
        }

        @Test
        @DisplayName("a merchant sale can only be VERIFIED, so a payment cannot be declared into existence")
        void merchantSaleMustBeVerified() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            1, COUNCIL, STALL, EntryKind.MERCHANT_SALE, Provenance.DECLARED,
                            kwacha(100), DAY, WHEN, "m", null))
                    .withMessageContaining("VERIFIED");
        }
    }

    @Nested
    @DisplayName("reversals fix mistakes without editing anything (rule 2)")
    class Reversals {

        @Test
        @DisplayName("a reversal has the opposite amount, the same provenance, and points at the original")
        void reversalOfBuildsTheOppositeEntry() {
            LedgerEntry original = deposit(1, 45_000, "dep-1");

            LedgerEntry reversal = LedgerEntry.reversalOf(original, 2, DAY, WHEN, "rev-1");

            assertThat(reversal.kind()).isEqualTo(EntryKind.REVERSAL);
            assertThat(reversal.amount().minorUnits()).isEqualTo(-45_000L);
            assertThat(reversal.provenance()).isEqualTo(Provenance.DEPOSITED);
            assertThat(reversal.reverses()).hasValue(1L);
            assertThat(original.amount().minorUnits()).as("the original is untouched").isEqualTo(45_000L);
            assertThat(original.reverses()).isEmpty();
        }

        @Test
        @DisplayName("a reversal must name the entry it reverses")
        void reversalMustPointAtSomething() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            2, COUNCIL, STALL, EntryKind.REVERSAL, Provenance.DEPOSITED,
                            kwacha(-100), DAY, WHEN, "r", null))
                    .withMessageContaining("must name");
        }

        @Test
        @DisplayName("a reversal must have a negative amount")
        void reversalMustBeNegative() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            2, COUNCIL, STALL, EntryKind.REVERSAL, Provenance.DEPOSITED,
                            kwacha(100), DAY, WHEN, "r", 1L))
                    .withMessageContaining("negative");
        }

        @Test
        @DisplayName("a reversal cannot point at itself")
        void reversalCannotReverseItself() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            2, COUNCIL, STALL, EntryKind.REVERSAL, Provenance.DEPOSITED,
                            kwacha(-100), DAY, WHEN, "r", 2L))
                    .withMessageContaining("different");
        }

        @Test
        @DisplayName("only a reversal may name a reversed entry")
        void nonReversalCannotPointAtAnEntry() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new LedgerEntry(
                            2, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                            kwacha(100), DAY, WHEN, "d", 1L))
                    .withMessageContaining("only a reversal");
        }

        @Test
        @DisplayName("a reversal cannot itself be reversed; reverse the original again with a new entry instead")
        void noReversalOfReversal() {
            LedgerEntry reversal = LedgerEntry.reversalOf(deposit(1, 100, "d"), 2, DAY, WHEN, "r");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> LedgerEntry.reversalOf(reversal, 3, DAY, WHEN, "rr"));
        }
    }

    @Nested
    @DisplayName("identity and idempotency key")
    class Identity {

        @Test
        @DisplayName("id must be positive")
        void idMustBePositive() {
            assertThatIllegalArgumentException().isThrownBy(() -> deposit(0, 100, "k"));
        }

        @Test
        @DisplayName("the idempotency key is required and bounded")
        void keyRequired() {
            assertThatIllegalArgumentException().isThrownBy(() -> deposit(1, 100, " "));
            assertThatIllegalArgumentException().isThrownBy(() -> deposit(1, 100, "x".repeat(201)));
            assertThat(deposit(1, 100, "x".repeat(200)).idempotencyKey()).hasSize(200);
        }

        @Test
        @DisplayName("nothing may be null")
        void nullsRefused() {
            assertThatNullPointerException().isThrownBy(() -> new LedgerEntry(
                    1, null, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED, kwacha(1), DAY, WHEN, "k", null));
            assertThatNullPointerException().isThrownBy(() -> new LedgerEntry(
                    1, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED, kwacha(1), DAY, WHEN, null, null));
        }
    }

    @Nested
    @DisplayName("canonical bytes, which the daily seal commits to")
    class CanonicalBytes {

        @Test
        @DisplayName("the same entry always gives the same bytes")
        void deterministic() {
            assertThat(deposit(1, 45_000, "dep-1").canonicalBytes())
                    .isEqualTo(deposit(1, 45_000, "dep-1").canonicalBytes());
        }

        @Test
        @DisplayName("changing any single field changes the bytes")
        void everyFieldMatters() {
            byte[] base = deposit(1, 45_000, "dep-1").canonicalBytes();

            assertThat(deposit(2, 45_000, "dep-1").canonicalBytes()).isNotEqualTo(base);
            assertThat(deposit(1, 45_001, "dep-1").canonicalBytes()).isNotEqualTo(base);
            assertThat(deposit(1, 45_000, "dep-2").canonicalBytes()).isNotEqualTo(base);
            assertThat(new LedgerEntry(1, UUID.randomUUID(), STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    kwacha(45_000), DAY, WHEN, "dep-1", null).canonicalBytes()).isNotEqualTo(base);
            assertThat(new LedgerEntry(1, COUNCIL, UUID.randomUUID(), EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    kwacha(45_000), DAY, WHEN, "dep-1", null).canonicalBytes()).isNotEqualTo(base);
            assertThat(new LedgerEntry(1, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    kwacha(45_000), DAY.plusDays(1), WHEN, "dep-1", null).canonicalBytes()).isNotEqualTo(base);
            assertThat(new LedgerEntry(1, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    kwacha(45_000), DAY, WHEN.plusSeconds(1), "dep-1", null).canonicalBytes()).isNotEqualTo(base);
            assertThat(new LedgerEntry(1, COUNCIL, STALL, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    kwacha(45_000), DAY, WHEN.plusNanos(1), "dep-1", null).canonicalBytes()).isNotEqualTo(base);
        }

        @Test
        @DisplayName("provenance and kind are part of the bytes")
        void provenanceMatters() {
            LedgerEntry declared = new LedgerEntry(1, COUNCIL, STALL, EntryKind.DECLARED_SALE,
                    Provenance.DECLARED, kwacha(100), DAY, WHEN, "k", null);
            LedgerEntry merchant = new LedgerEntry(1, COUNCIL, STALL, EntryKind.MERCHANT_SALE,
                    Provenance.VERIFIED, kwacha(100), DAY, WHEN, "k", null);

            assertThat(declared.canonicalBytes()).isNotEqualTo(merchant.canonicalBytes());
        }

        @Test
        @DisplayName("keys that differ only in where one field ends and the next begins do not collide")
        void lengthPrefixPreventsAmbiguity() {
            LedgerEntry a = deposit(1, 100, "ab");
            LedgerEntry b = deposit(1, 100, "a");

            assertThat(a.canonicalBytes()).isNotEqualTo(b.canonicalBytes());
        }
    }
}
