package zm.msika.api.security;

import static zm.msika.core.user.Permission.ALLOCATE_STALLS;
import static zm.msika.core.user.Permission.CLOSE_DAYS;
import static zm.msika.core.user.Permission.CREATE_COUNCILS;
import static zm.msika.core.user.Permission.DECIDE_REGISTRATIONS;
import static zm.msika.core.user.Permission.MANAGE_LEVY;
import static zm.msika.core.user.Permission.MANAGE_MARKETS;
import static zm.msika.core.user.Permission.MANAGE_PLANS;
import static zm.msika.core.user.Permission.MANAGE_POLICIES;
import static zm.msika.core.user.Permission.MANAGE_USERS;
import static zm.msika.core.user.Permission.MANAGE_WALLETS;
import static zm.msika.core.user.Permission.READ;
import static zm.msika.core.user.Permission.READ_AUDIT;
import static zm.msika.core.user.Permission.REGISTER_PEOPLE;
import static zm.msika.core.user.Permission.RUN_RECONCILIATION;
import static zm.msika.core.user.Permission.SETTLE_DEPOSITS;
import static zm.msika.core.user.Permission.SIMULATE;
import static zm.msika.core.user.Permission.START_DEPOSITS;
import static zm.msika.core.user.Permission.USE_INBOX;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import zm.msika.core.user.Permission;

/**
 * What each endpoint needs (card M-61): one permission, and for actions inside a market, how to find that market. The
 * whole list is here so it can be read in one go. An endpoint missing from it is refused, and fails
 * {@code RolesApiTest}.
 */
public final class EndpointPermissions {

    /** Where the market an action belongs to comes from. */
    public enum MarketFrom {
        /** The action is not inside one market. */
        NONE,
        /** The path names it. */
        PATH,
        /** The path names a stall, plan, deposit or registration, which belongs to a market. */
        STALL, PLAN, DEPOSIT, REGISTRATION
    }

    public record Rule(Permission permission, MarketFrom market) {}

    /** Endpoints that prove the caller some other way, or need no more than being signed in. */
    private static final Set<String> OPEN = Set.of("POST /api/v1/auth/login", "GET /api/v1/auth/me",
            "POST /api/v1/payments/callback", "POST /api/v1/sms/inbound", "GET /api/v1/seal-key");

    private static final String C = "/api/v1/councils/{councilId}";
    private static final Map<String, Rule> RULES = new HashMap<>();

    private static void rule(String endpoint, Permission permission, MarketFrom market) {
        if (RULES.put(endpoint, new Rule(permission, market)) != null) {
            throw new IllegalStateException(endpoint + " is listed twice");
        }
    }

    private static void read(String... paths) {
        for (String p : paths) {
            rule("GET " + C + p, READ, MarketFrom.NONE);
        }
    }

    static {
        read("/agents", "/agents/{agentId}/round", "/inbox", "/policies", "/requests/{id}", "/deposits", "/deposits/{id}", "/levy-charges", "/markets",
                "/markets/{marketId}/layout-config", "/markets/{marketId}/levy-rules", "/markets/{marketId}/map-status",
                "/markets/{marketId}/map-events", "/markets/{marketId}/plan", "/markets/{marketId}/plans",
                "/markets/{marketId}/plates.pdf", "/markets/{marketId}/stalls", "/plans/{planId}", "/reconciliation/{date}",
                "/registrations", "/reports/agents", "/reports/arrears", "/reports/collections", "/reports/markets",
                "/reports/reconciliation", "/reports/seals", "/reports/seals/verification", "/seals/{date}", "/search",
                "/stalls/{stallId}/panel", "/traders", "/wallets");
        rule("GET /api/v1/seals/{date}", READ, MarketFrom.NONE);
        rule("POST " + C + "/requests", USE_INBOX, MarketFrom.NONE);
        rule("POST " + C + "/requests/{id}/decisions", USE_INBOX, MarketFrom.NONE);
        rule("POST " + C + "/requests/{id}/withdraw", USE_INBOX, MarketFrom.NONE);
        rule("POST " + C + "/requests/bulk-decisions", USE_INBOX, MarketFrom.NONE);
        rule("POST " + C + "/requests/hand-over", USE_INBOX, MarketFrom.NONE);
        rule("PUT " + C + "/policies/{kind}", MANAGE_POLICIES, MarketFrom.NONE);
        rule("GET " + C + "/audit-log", READ_AUDIT, MarketFrom.NONE);
        rule("GET " + C + "/users", MANAGE_USERS, MarketFrom.NONE);
        rule("POST " + C + "/users", MANAGE_USERS, MarketFrom.NONE);
        rule("PUT " + C + "/users/{userId}/active", MANAGE_USERS, MarketFrom.NONE);
        rule("POST " + C + "/users/{userId}/roles", MANAGE_USERS, MarketFrom.NONE);
        rule("DELETE " + C + "/users/{userId}/roles/{role}", MANAGE_USERS, MarketFrom.NONE);
        rule("POST " + C + "/markets", MANAGE_MARKETS, MarketFrom.NONE);
        rule("POST " + C + "/markets/{marketId}/stalls/import", MANAGE_MARKETS, MarketFrom.PATH);
        rule("PUT " + C + "/stalls/{stallId}/allocation", ALLOCATE_STALLS, MarketFrom.STALL);
        rule("DELETE " + C + "/stalls/{stallId}/allocation", ALLOCATE_STALLS, MarketFrom.STALL);
        rule("POST " + C + "/traders", REGISTER_PEOPLE, MarketFrom.NONE);
        rule("POST " + C + "/agents", REGISTER_PEOPLE, MarketFrom.NONE);
        rule("POST " + C + "/registrations/{id}/approve", DECIDE_REGISTRATIONS, MarketFrom.REGISTRATION);
        rule("POST " + C + "/registrations/{id}/reject", DECIDE_REGISTRATIONS, MarketFrom.REGISTRATION);
        rule("POST " + C + "/deposits", START_DEPOSITS, MarketFrom.NONE);
        rule("POST " + C + "/deposits/{id}/cancel", SETTLE_DEPOSITS, MarketFrom.DEPOSIT);
        rule("POST " + C + "/deposits/{id}/dispute", SETTLE_DEPOSITS, MarketFrom.DEPOSIT);
        rule("POST " + C + "/wallets", MANAGE_WALLETS, MarketFrom.NONE);
        rule("POST " + C + "/markets/{marketId}/levy-rules", MANAGE_LEVY, MarketFrom.PATH);
        rule("POST " + C + "/levy-charges/run", MANAGE_LEVY, MarketFrom.NONE);
        rule("POST " + C + "/markets/{marketId}/plans", MANAGE_PLANS, MarketFrom.PATH);
        rule("PUT " + C + "/markets/{marketId}/layout-config", MANAGE_PLANS, MarketFrom.PATH);
        rule("PUT " + C + "/plans/{planId}", MANAGE_PLANS, MarketFrom.PLAN);
        rule("POST " + C + "/plans/{planId}/publish", MANAGE_PLANS, MarketFrom.PLAN);
        rule("POST " + C + "/reconciliation/{date}", RUN_RECONCILIATION, MarketFrom.NONE);
        rule("POST " + C + "/day-close", CLOSE_DAYS, MarketFrom.NONE);
        rule("POST /api/v1/councils", CREATE_COUNCILS, MarketFrom.NONE);
        rule("GET /api/v1/sim/phones", SIMULATE, MarketFrom.NONE);
        rule("GET /api/v1/sim/sms", SIMULATE, MarketFrom.NONE);
        rule("POST /api/v1/sim/sms", SIMULATE, MarketFrom.NONE);
    }

    private EndpointPermissions() {}

    public static boolean isOpen(String method, String pattern) {
        return OPEN.contains(method + " " + pattern);
    }

    public static Optional<Rule> find(String method, String pattern) {
        return Optional.ofNullable(RULES.get(("HEAD".equals(method) ? "GET" : method) + " " + pattern));
    }

    /** Every endpoint the list names, for the test that checks none is left over. */
    static Set<String> listed() {
        Set<String> all = new java.util.HashSet<>(RULES.keySet());
        all.addAll(OPEN);
        return all;
    }
}
