package zm.msika.ledger;

import java.util.Objects;

/**
 * A monetary amount, held as a whole number of minor units.
 *
 * <p><strong>No floating point, made structural (AGENTS.md rule 1).</strong> There is no constructor, factory or
 * accessor on this type that accepts or returns {@code double} or {@code float}. Binary floating
 * point cannot represent decimal fractions exactly, so repeated arithmetic silently loses value.
 * In a ledger ledger that is not a rounding artefact — it is money that stopped existing, and
 * and no test on the code above it could localise the loss. The defence has to live in the type, not in the discipline of whoever writes the next
 * class.
 *
 * <p><strong>GRASP.</strong> Value Object and Information Expert (Larman Ch. 17). {@code Money}
 * owns everything knowable about a monetary amount: whether it is negative, how to add it, and
 * whether adding it would overflow. No service reaches in to do arithmetic on a raw {@code long}.
 *
 * <p><strong>Overflow is an error, never a wrap-around.</strong> Every operation uses the
 * {@code Math.*Exact} family, so a computation that cannot be represented throws
 * {@link ArithmeticException} instead of quietly producing a wrong balance. A silently wrapped
 * balance in a ledger is the worst possible failure: it is wrong, it is
 * plausible, and nothing downstream can detect it.
 *
 * <p>Instances are immutable. Every operation returns a new instance.
 *
 * @param minorUnits the amount in the currency minor unit, for example ngwee for ZMW
 * @param currency the currency; amounts in different currencies are never commensurable
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    /** Validates on construction, so an invalid {@code Money} cannot exist. */
    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /** Creates an amount from whole minor units, for example 150 ngwee. */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * Creates an amount from whole major units, for example 150 kwacha.
     *
     * @throws ArithmeticException if the amount overflows {@code long} minor units
     */
    public static Money ofMajor(long majorUnits, Currency currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return new Money(
                Math.multiplyExact(majorUnits, pow10(currency.minorUnitDigits())), currency);
    }

    /** The zero amount in the given currency. */
    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Returns this amount plus {@code other}.
     *
     * @throws IllegalArgumentException if the currencies differ
     * @throws ArithmeticException if the result overflows
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * Returns this amount minus {@code other}.
     *
     * @throws IllegalArgumentException if the currencies differ
     * @throws ArithmeticException if the result overflows
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * Returns the additive inverse. Used to build the credit side of a balanced posting pair.
     *
     * @throws ArithmeticException if this amount is {@code Long.MIN_VALUE}, which has no
     *     representable negation
     */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    /** Returns {@code true} if this amount is exactly zero. */
    public boolean isZero() {
        return minorUnits == 0L;
    }

    /** Returns {@code true} if this amount is strictly less than zero. */
    public boolean isNegative() {
        return minorUnits < 0L;
    }

    /** Returns {@code true} if this amount is strictly greater than zero. */
    public boolean isPositive() {
        return minorUnits > 0L;
    }

    /**
     * Returns {@code true} if this amount is at least {@code other}. Used for checks such as
     * "is there enough money for this payment".
     *
     * @throws IllegalArgumentException if the currencies differ
     */
    public boolean isAtLeast(Money other) {
        requireSameCurrency(other);
        return minorUnits >= other.minorUnits;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the currencies differ, since amounts in different
     *     currencies have no ordering relative to one another
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (currency != other.currency) {
            throw new IllegalArgumentException(
                    "currency mismatch: cannot combine "
                            + currency.alphabeticCode()
                            + " with "
                            + other.currency.alphabeticCode());
        }
    }

    private static long pow10(int exponent) {
        long result = 1L;
        for (int i = 0; i < exponent; i++) {
            result = Math.multiplyExact(result, 10L);
        }
        return result;
    }

    /**
     * Renders as {@code ZMW 1234.56}.
     *
     * <p>Formatting is done on the decimal string rather than by dividing, so that
     * {@code Long.MIN_VALUE} formats correctly. {@code Math.abs(Long.MIN_VALUE)} is itself
     * negative, and a {@code toString} that throws or lies on an edge value is a bad thing to
     * discover inside an incident.
     */
    @Override
    public String toString() {
        int digits = currency.minorUnitDigits();
        String unsigned = Long.toString(minorUnits);
        String sign = "";
        if (unsigned.startsWith("-")) {
            sign = "-";
            unsigned = unsigned.substring(1);
        }
        if (digits == 0) {
            return currency.alphabeticCode() + " " + sign + unsigned;
        }
        StringBuilder padded = new StringBuilder(unsigned);
        while (padded.length() <= digits) {
            padded.insert(0, '0');
        }
        int split = padded.length() - digits;
        return currency.alphabeticCode()
                + " "
                + sign
                + padded.substring(0, split)
                + "."
                + padded.substring(split);
    }
}
