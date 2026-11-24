package zm.msika.core.request;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Deadlines in working days: Monday to Friday. Public holidays are not known to Msika yet. */
public final class WorkingDays {

    private WorkingDays() {}

    /** The end of the working day {@code days} working days after {@code from}. */
    public static Instant due(Instant from, int days, ZoneId zone) {
        LocalDate day = from.atZone(zone).toLocalDate();
        for (int left = days; left > 0;) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
