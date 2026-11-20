package zm.msika.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * specification for {@link Money}.
 *
 * <p>These tests are the specification, not a coverage exercise. Each one corresponds to a way a
 * ledger ledger can lose money.
 */
class MoneyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("ofMajor scales by the currency minor unit digits")
        void ofMajorScalesCorrectly() {
            assertThat(Money.ofMajor(150, Currency.ZMW).minorUnits()).isEqualTo(15_000L);
        }

        @Test
        @DisplayName("ofMinor stores minor units verbatim")
        void ofMinorIsVerbatim() {
            assertThat(Money.ofMinor(15_050L, Currency.ZMW).minorUnits()).isEqualTo(15_050L);
        }

        @Test
        @DisplayName("zero is zero")
        void zeroIsZero() {
            assertThat(Money.zero(Currency.ZMW).isZero()).isTrue();
        }

        @Test
        @DisplayName("currency is required, so an invalid Money cannot exist")
        void currencyIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> Money.ofMinor(1L, null));
        }

        @Test
        @DisplayName("ofMajor refuses to overflow rather than wrapping")
        void ofMajorRejectsOverflow() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.ofMajor(Long.MAX_VALUE, Currency.ZMW));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("plus adds within the same currency")
        void plusAdds() {
            Money a = Money.ofMajor(100, Currency.ZMW);
            Money b = Money.ofMajor(50, Currency.ZMW);
            assertThat(a.plus(b)).isEqualTo(Money.ofMajor(150, Currency.ZMW));
        }

        @Test
        @DisplayName("minus subtracts within the same currency")
        void minusSubtracts() {
            Money a = Money.ofMajor(100, Currency.ZMW);
            Money b = Money.ofMajor(30, Currency.ZMW);
            assertThat(a.minus(b)).isEqualTo(Money.ofMajor(70, Currency.ZMW));
        }

        @Test
        @DisplayName("minus can go negative, which is legitimate for a posting")
        void minusCanGoNegative() {
            Money result = Money.ofMajor(10, Currency.ZMW).minus(Money.ofMajor(30, Currency.ZMW));
            assertThat(result.isNegative()).isTrue();
            assertThat(result.minorUnits()).isEqualTo(-2_000L);
        }

        @Test
        @DisplayName("negate produces the credit side of a posting pair")
        void negateInverts() {
            Money debit = Money.ofMajor(100, Currency.ZMW);
            assertThat(debit.negate().plus(debit).isZero()).isTrue();
        }

        @Test
        @DisplayName("addition overflow throws instead of silently wrapping")
        void additionOverflowThrows() {
            Money max = Money.ofMinor(Long.MAX_VALUE, Currency.ZMW);
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> max.plus(Money.ofMinor(1L, Currency.ZMW)));
        }

        @Test
        @DisplayName("subtraction overflow throws instead of silently wrapping")
        void subtractionOverflowThrows() {
            Money min = Money.ofMinor(Long.MIN_VALUE, Currency.ZMW);
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> min.minus(Money.ofMinor(1L, Currency.ZMW)));
        }

        @Test
        @DisplayName("negating Long.MIN_VALUE throws, since it has no representable inverse")
        void negatingMinValueThrows() {
            Money min = Money.ofMinor(Long.MIN_VALUE, Currency.ZMW);
            assertThatExceptionOfType(ArithmeticException.class).isThrownBy(min::negate);
        }
    }

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        @DisplayName("adding across currencies is refused")
        void plusAcrossCurrenciesThrows() {
            Money kwacha = Money.ofMajor(100, Currency.ZMW);
            Money dollars = Money.ofMajor(100, Currency.USD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> kwacha.plus(dollars))
                    .withMessageContaining("currency mismatch");
        }

        @Test
        @DisplayName("comparing across currencies is refused, since there is no such ordering")
        void compareAcrossCurrenciesThrows() {
            Money kwacha = Money.ofMajor(100, Currency.ZMW);
            Money dollars = Money.ofMajor(100, Currency.USD);
            assertThatIllegalArgumentException().isThrownBy(() -> kwacha.compareTo(dollars));
        }

        @Test
        @DisplayName("equal minor units in different currencies are not equal")
        void differentCurrenciesAreNotEqual() {
            assertThat(Money.ofMinor(100L, Currency.ZMW))
                    .isNotEqualTo(Money.ofMinor(100L, Currency.USD));
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("isAtLeast expresses the sufficient-funds check")
        void isAtLeastComparesCorrectly() {
            Money balance = Money.ofMajor(100, Currency.ZMW);
            assertThat(balance.isAtLeast(Money.ofMajor(100, Currency.ZMW))).isTrue();
            assertThat(balance.isAtLeast(Money.ofMajor(99, Currency.ZMW))).isTrue();
            assertThat(balance.isAtLeast(Money.ofMajor(101, Currency.ZMW))).isFalse();
        }

        @Test
        @DisplayName("compareTo orders by minor units")
        void compareToOrders() {
            assertThat(Money.ofMajor(1, Currency.ZMW))
                    .isLessThan(Money.ofMajor(2, Currency.ZMW))
                    .isGreaterThan(Money.ofMajor(0, Currency.ZMW));
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        @DisplayName("renders with the decimal point in the right place")
        void rendersWholeAndFraction() {
            assertThat(Money.ofMinor(123_456L, Currency.ZMW)).hasToString("ZMW 1234.56");
        }

        @Test
        @DisplayName("pads amounts smaller than one major unit")
        void padsSmallAmounts() {
            assertThat(Money.ofMinor(5L, Currency.ZMW)).hasToString("ZMW 0.05");
            assertThat(Money.ofMinor(0L, Currency.ZMW)).hasToString("ZMW 0.00");
        }

        @Test
        @DisplayName("renders negatives with the sign before the amount")
        void rendersNegatives() {
            assertThat(Money.ofMinor(-123_456L, Currency.ZMW)).hasToString("ZMW -1234.56");
            assertThat(Money.ofMinor(-5L, Currency.ZMW)).hasToString("ZMW -0.05");
        }

        @Test
        @DisplayName("renders Long.MIN_VALUE without throwing or lying")
        void rendersMinValue() {
            // Math.abs(Long.MIN_VALUE) is itself negative. A toString that throws or lies on an
            // edge value is a bad thing to discover during an incident.
            assertThat(Money.ofMinor(Long.MIN_VALUE, Currency.ZMW))
                    .hasToString("ZMW -92233720368547758.08");
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal amounts in the same currency are equal and hash alike")
        void valueEquality() {
            Money a = Money.ofMajor(100, Currency.ZMW);
            Money b = Money.ofMinor(10_000L, Currency.ZMW);
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("operations return new instances and never mutate the receiver")
        void operationsDoNotMutate() {
            Money original = Money.ofMajor(100, Currency.ZMW);
            Money unused = original.plus(Money.ofMajor(50, Currency.ZMW));
            assertThat(unused).isNotSameAs(original);
            assertThat(original.minorUnits()).isEqualTo(10_000L);
        }
    }
}
