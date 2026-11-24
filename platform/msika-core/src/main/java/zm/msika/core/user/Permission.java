package zm.msika.core.user;

/** One thing an endpoint lets a user do. Who holds which is decided in {@link Permissions}, and only there. */
public enum Permission {
    READ, READ_AUDIT, SEE_PHONES, MANAGE_USERS, MANAGE_MARKETS, REGISTER_PEOPLE, DECIDE_REGISTRATIONS, ALLOCATE_STALLS,
    MANAGE_PLANS, MANAGE_LEVY, START_DEPOSITS, SETTLE_DEPOSITS, MANAGE_WALLETS, RUN_RECONCILIATION, CLOSE_DAYS,
    CREATE_COUNCILS, SIMULATE
}
