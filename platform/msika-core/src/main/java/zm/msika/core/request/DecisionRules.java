package zm.msika.core.request;

import java.util.Collection;
import java.util.UUID;
import zm.msika.core.user.Role;
import zm.msika.core.user.RoleGrant;

/** Who may decide a request (COUNCIL-ADMIN.md sections 2 and 3.3). The database checks the first two rules as well. */
public final class DecisionRules {

    private DecisionRules() {}

    /**
     * Why this person may not decide now, or null if they may.
     *
     * @param approvals how many approvals the request has so far
     * @param alreadyApproved whether this person gave one of them
     * @param escalated whether the request is overdue, which lets a council admin decide it as well
     */
    public static String refusal(RequestPolicy policy, int approvals, UUID marketId, boolean raisedIt,
            boolean alreadyApproved, boolean escalated, Collection<RoleGrant> grants) {
        if (raisedIt) {
            return "the person who raised a request cannot decide it";
        }
        if (alreadyApproved) {
            return "one person cannot give two approvals";
        }
        if (approvals >= policy.steps().size()) {
            return "the request has all its approvals";
        }
        var roles = policy.steps().get(approvals);
        boolean held = grants.stream().anyMatch(g -> (roles.contains(g.role()) || (escalated && g.role() == Role.COUNCIL_ADMIN))
                && (!g.role().marketScoped() || (marketId != null && g.marketIds().contains(marketId))));
        return held ? null : "this approval needs " + String.join(" or ", roles.stream().map(Role::name).sorted().toList());
    }
}
