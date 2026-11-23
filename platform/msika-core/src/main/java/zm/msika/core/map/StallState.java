package zm.msika.core.map;

import java.time.LocalDate;

/**
 * What a stall's colour on the map means (MARKET-MAP section 7). Worked out every time from the register and recent
 * activity, never stored. Pure, so each rule and each edge is tested on its own.
 */
public final class StallState {

    /** The main layer. */
    public enum State {
        /** Nobody holds it (status VACANT). */
        FREE,
        /** Held, and a deposit or verified sale within the quiet period. */
        ACTIVE,
        /** Held, and nothing within the quiet period. */
        QUIET,
        /** Status SUSPENDED. */
        SUSPENDED
    }

    /** The levy layer. */
    public enum LevyBand {
        /** No unpaid levy days. */
        PAID_UP,
        /** One to seven unpaid days. */
        OWED_1_TO_7,
        /** More than seven unpaid days. */
        OWED_OVER_7,
        /** Nobody holds the stall, so nobody owes. */
        NONE
    }

    /** Days without trade after which an allocated stall counts as quiet (MARKET-MAP section 7: configurable). */
    public static final int DEFAULT_QUIET_DAYS = 7;

    private StallState() {}

    /**
     * @param status the register's status: VACANT, ALLOCATED or SUSPENDED
     * @param lastActivity the last business date with a deposit or verified sale, or null if never
     * @param day the day the map is drawn for
     * @param quietDays how many days without trade make a stall quiet
     */
    public static State state(String status, LocalDate lastActivity, LocalDate day, int quietDays) {
        return switch (status) {
            case "VACANT" -> State.FREE;
            case "SUSPENDED" -> State.SUSPENDED;
            default -> lastActivity != null && lastActivity.isAfter(day.minusDays(quietDays)) && !lastActivity.isAfter(day)
                    ? State.ACTIVE : State.QUIET;
        };
    }

    public static LevyBand levyBand(boolean held, int unpaidDays) {
        if (!held) {
            return LevyBand.NONE;
        }
        if (unpaidDays <= 0) {
            return LevyBand.PAID_UP;
        }
        return unpaidDays <= 7 ? LevyBand.OWED_1_TO_7 : LevyBand.OWED_OVER_7;
    }
}
