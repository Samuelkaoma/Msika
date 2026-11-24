package zm.msika.core.user;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The permission matrix: role to permissions, in code (COUNCIL-ADMIN.md section 3.2). Anything not listed is refused.
 * The {@code role_permission} table is re-seeded from here on every start and is only ever a copy.
 */
public final class Permissions {

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        // Operators do everything until support access (M-70) takes council data away from them.
        MATRIX.put(Role.MSIKA_OPERATOR, EnumSet.allOf(Permission.class));
        MATRIX.put(Role.COUNCIL_ADMIN, EnumSet.complementOf(
                EnumSet.of(Permission.CLOSE_DAYS, Permission.CREATE_COUNCILS, Permission.SIMULATE)));
        MATRIX.put(Role.MARKET_MASTER, EnumSet.of(Permission.READ, Permission.DECIDE_REGISTRATIONS,
                Permission.ALLOCATE_STALLS, Permission.MANAGE_PLANS, Permission.SETTLE_DEPOSITS));
        MATRIX.put(Role.REVENUE_OFFICER, EnumSet.of(Permission.READ));
        MATRIX.put(Role.ENFORCEMENT_OFFICER, EnumSet.of(Permission.READ));
        MATRIX.put(Role.COUNCIL_OFFICER, EnumSet.of(Permission.READ));
        MATRIX.put(Role.AUDITOR, EnumSet.of(Permission.READ, Permission.READ_AUDIT));
    }

    private Permissions() {}

    public static Set<Permission> of(Role role) {
        return Set.copyOf(MATRIX.get(role));
    }

    /**
     * @param marketId the market the action is in, or null for an action on the whole council. A market-scoped role
     *     may read council-wide, but may change things only in its own markets.
     */
    public static boolean allows(Collection<RoleGrant> grants, Permission permission, UUID marketId) {
        return grants.stream().anyMatch(g -> MATRIX.get(g.role()).contains(permission)
                && (!g.role().marketScoped() || permission == Permission.READ
                        || (marketId != null && g.marketIds().contains(marketId))));
    }
}
