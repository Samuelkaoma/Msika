package zm.msika.core.phone;

import java.util.Objects;
import java.util.regex.Pattern;
import zm.msika.core.common.InvalidInputException;

/**
 * A Zambian mobile number in E.164 form, like {@code +260977123739} (AGENTS.md: phone numbers are
 * E.164 once they enter the system, and masked in logs).
 *
 * <p>{@link #parse} accepts the ways people actually write a number and turns them all into the same
 * value: {@code 0977 123 739}, {@code 260977123739}, {@code +260977123739}, {@code 00260977123739},
 * {@code (097) 7123-739}. Anything else is refused.
 *
 * <p><strong>{@link #toString()} is masked on purpose.</strong> A phone number is personal data, and
 * the easiest way to leak one is to log an object that contains it. Printing a {@code PhoneNumber}
 * (or a record holding one) gives {@code 0977***739}. Use {@link #e164()} when you really need the
 * full number, for example to send an SMS or store it.
 *
 * @param e164 the number as {@code +260} followed by nine digits that start with 7 or 9
 */
public record PhoneNumber(String e164) {

    private static final Pattern VALID = Pattern.compile("\\+260[79][0-9]{8}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()]");

    public PhoneNumber {
        Objects.requireNonNull(e164, "e164 must not be null");
        if (!VALID.matcher(e164).matches()) {
            throw new InvalidInputException("phone number is not a valid Zambian mobile number");
        }
    }

    /**
     * Reads a number written in any of the usual ways.
     *
     * @throws InvalidInputException if it is not a Zambian mobile number
     */
    public static PhoneNumber parse(String raw) {
        if (raw == null) {
            throw new InvalidInputException("phone number is required");
        }
        String cleaned = SEPARATORS.matcher(raw.trim()).replaceAll("");
        String digits;
        if (cleaned.startsWith("+")) {
            digits = cleaned.substring(1);
        } else if (cleaned.startsWith("00")) {
            digits = cleaned.substring(2);
        } else {
            digits = cleaned;
        }
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new InvalidInputException("phone number is not a valid Zambian mobile number");
        }

        String national;
        if (digits.startsWith("260")) {
            national = digits.substring(3);
        } else if (digits.startsWith("0")) {
            national = digits.substring(1);
        } else if (cleaned.startsWith("+") || cleaned.startsWith("00")) {
            // An explicit international prefix for some other country.
            throw new InvalidInputException("phone number is not a valid Zambian mobile number");
        } else {
            national = digits;
        }
        return new PhoneNumber("+260" + national);
    }

    /** The number as people write it locally, like {@code 0977123739}. */
    public String national() {
        return "0" + e164.substring(4);
    }

    /** A form safe for logs: the first four and last three digits, like {@code 0977***739}. */
    public String masked() {
        String national = national();
        return national.substring(0, 4) + "***" + national.substring(national.length() - 3);
    }

    /** Masked, so a phone number cannot reach a log in full by accident. */
    @Override
    public String toString() {
        return masked();
    }
}
