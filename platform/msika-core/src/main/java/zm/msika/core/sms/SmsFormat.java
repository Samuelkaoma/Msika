package zm.msika.core.sms;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/** How values are written in replies: the same everywhere, and short. */
public final class SmsFormat {

    private SmsFormat() {}

    /**
     * An amount in kwacha: {@code K450}, {@code K12.50}, {@code K1,230.00} when {@code alwaysDecimals}. Integer
     * arithmetic only (AGENTS.md rule 1). Negative amounts are written {@code -K5}.
     */
    public static String kwacha(long ngwee, boolean alwaysDecimals) {
        boolean negative = ngwee < 0;
        long abs = Math.abs(ngwee);
        long whole = abs / 100;
        long fraction = abs % 100;
        String grouped = String.format(Locale.ROOT, "%,d", whole);
        String text = "K" + grouped + (fraction != 0 || alwaysDecimals ? "." + String.format(Locale.ROOT, "%02d", fraction) : "");
        return negative ? "-" + text : text;
    }

    /** An amount in kwacha, with decimals only when there are some ngwee. */
    public static String kwacha(long ngwee) {
        return kwacha(ngwee, false);
    }

    /** A date as {@code 20 Nov}. */
    public static String day(LocalDate date) {
        return date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** A month as {@code Oct}. */
    public static String month(LocalDate anyDayOfIt) {
        return anyDayOfIt.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** A month in full, as {@code October}. */
    public static String monthName(LocalDate anyDayOfIt) {
        return anyDayOfIt.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
