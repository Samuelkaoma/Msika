package zm.msika.ledger;

/**
 * Currencies Msika can record. Only ZMW is used in the pilot; the others exist so that mixing currencies is detected.
 *
 * <p>A deliberate domain type rather than {@link java.util.Currency}. The JDK type is driven by
 * locale data that can change between JDK versions, and the set of currencies we handle is small, fixed, and a matter of policy rather than of
 * platform. Making it an enum means an unsupported currency cannot be represented at all.
 *
 * <p>{@code minorUnitDigits} is the number of decimal places in the currency, per ISO 4217. It
 * exists so that presentation can format correctly; it is never used for arithmetic, which is
 * always performed in whole minor units.
 */
public enum Currency {

    /** Zambian Kwacha. The currency Msika records in. */
    ZMW("ZMW", 967, 2),

    /** United States Dollar. Not used in the pilot. */
    USD("USD", 840, 2),

    /** Euro. */
    EUR("EUR", 978, 2),

    /** Pound Sterling. */
    GBP("GBP", 826, 2),

    /** South African Rand. Significant regional corridor. */
    ZAR("ZAR", 710, 2);

    private final String alphabeticCode;
    private final int numericCode;
    private final int minorUnitDigits;

    Currency(String alphabeticCode, int numericCode, int minorUnitDigits) {
        this.alphabeticCode = alphabeticCode;
        this.numericCode = numericCode;
        this.minorUnitDigits = minorUnitDigits;
    }

    /** ISO 4217 alphabetic code, for example {@code ZMW}. */
    public String alphabeticCode() {
        return alphabeticCode;
    }

    /** ISO 4217 numeric code, as carried in ISO 20022 messages. */
    public int numericCode() {
        return numericCode;
    }

    /** Number of decimal places, per ISO 4217. Presentation only, never arithmetic. */
    public int minorUnitDigits() {
        return minorUnitDigits;
    }
}
