package zm.msika.core.levy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Which unpaid levy charges a confirmed deposit pays (ARCHITECTURE.md section 4.3, card M-24). Pure.
 *
 * <p>The oldest days are paid first, whole days only, as many as the deposit covers. A deposit smaller than one
 * day's levy pays nothing, and the trader keeps the money: paying part of a day would leave a charge that is
 * neither paid nor unpaid, which no one could explain on a receipt.
 */
public final class LevySweepPlan {

    /** One unpaid day. */
    public record Unpaid(UUID chargeId, LocalDate businessDate, long amountNgwee) {}

    private LevySweepPlan() {}

    /** The charges to pay from a deposit of {@code depositNgwee}, oldest first. */
    public static List<Unpaid> choose(List<Unpaid> unpaid, long depositNgwee) {
        List<Unpaid> sorted = new ArrayList<>(unpaid);
        sorted.sort(Comparator.comparing(Unpaid::businessDate).thenComparing(Unpaid::chargeId));
        List<Unpaid> chosen = new ArrayList<>();
        long left = depositNgwee;
        for (Unpaid u : sorted) {
            if (u.amountNgwee() > left) {
                break;
            }
            chosen.add(u);
            left -= u.amountNgwee();
        }
        return List.copyOf(chosen);
    }

    public static long total(List<Unpaid> charges) {
        return charges.stream().mapToLong(Unpaid::amountNgwee).sum();
    }
}
