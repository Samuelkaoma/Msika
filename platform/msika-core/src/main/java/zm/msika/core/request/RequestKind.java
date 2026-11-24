package zm.msika.core.request;

import java.util.List;
import java.util.Set;
import zm.msika.core.user.Role;

/** What a request asks for, who may raise it, and the minimum policy a council may only tighten (section 4.2). */
public enum RequestKind {
    STALL_CLASS("STALL", 5, true, List.of(Set.of(Role.MARKET_MASTER, Role.COUNCIL_ADMIN))),
    STALL_REINSTATE("STALL", 5, true, List.of(Set.of(Role.MARKET_MASTER, Role.COUNCIL_ADMIN))),
    STALL_SUSPEND("STALL", 5, false,
            List.of(Set.of(Role.MARKET_MASTER, Role.COUNCIL_ADMIN), Set.of(Role.COUNCIL_ADMIN))),
    AGENT_SUSPENSION("AGENT", 3, false, List.of(Set.of(Role.COUNCIL_ADMIN)));

    private final String subjectType;
    private final RequestPolicy minimum;

    RequestKind(String subjectType, int deadlineDays, boolean bulk, List<Set<Role>> steps) {
        this.subjectType = subjectType;
        this.minimum = new RequestPolicy(steps, deadlineDays, bulk, 0);
    }

    public String subjectType() {
        return subjectType;
    }

    public RequestPolicy minimum() {
        return minimum;
    }

    public Role raiser() {
        return Role.MARKET_MASTER;
    }
}
