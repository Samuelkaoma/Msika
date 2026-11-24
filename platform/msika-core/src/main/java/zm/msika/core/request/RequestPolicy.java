package zm.msika.core.request;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.user.Role;

/**
 * How one kind of request is decided in one council.
 *
 * @param steps who may give each approval, in order; there is one set of roles for each approval
 * @param deadlineDays working days
 * @param sampleRate 0 for none; 10 means one in ten must be opened before a bulk approval
 */
public record RequestPolicy(List<Set<Role>> steps, int deadlineDays, boolean bulkAllowed, int sampleRate) {

    private static final Set<Role> DECIDERS = Set.of(Role.COUNCIL_ADMIN, Role.MARKET_MASTER, Role.REVENUE_OFFICER);

    public RequestPolicy {
        Objects.requireNonNull(steps);
        steps = steps.stream().map(Set::copyOf).toList();
        if (steps.isEmpty() || steps.size() > 4 || steps.stream().anyMatch(s -> s.isEmpty() || !DECIDERS.containsAll(s))) {
            throw new InvalidInputException("a policy needs 1 to 4 approvals, each by at least one role that may decide");
        }
        if (deadlineDays < 1 || sampleRate < 0) {
            throw new InvalidInputException("deadline days must be at least 1 and the sample rate 0 or more");
        }
    }

    /** @throws InvalidInputException if this is looser than {@code minimum} in any way */
    public RequestPolicy requireAtLeast(RequestPolicy minimum) {
        boolean steps = this.steps.size() >= minimum.steps.size();
        for (int i = 0; steps && i < minimum.steps.size(); i++) {
            steps = minimum.steps.get(i).containsAll(this.steps.get(i));
        }
        boolean sample = minimum.sampleRate == 0 || (sampleRate > 0 && sampleRate <= minimum.sampleRate);
        if (!steps || deadlineDays > minimum.deadlineDays || (bulkAllowed && !minimum.bulkAllowed) || !sample) {
            throw new InvalidInputException("a council can tighten a policy but not loosen it below the minimum");
        }
        return this;
    }

    /** How many of {@code items} must have been opened before they can be approved together. */
    public int sampleOf(int items) {
        return sampleRate == 0 ? 0 : (items + sampleRate - 1) / sampleRate;
    }
}
