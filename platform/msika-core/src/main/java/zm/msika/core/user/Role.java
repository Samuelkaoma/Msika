package zm.msika.core.user;

/** A role a user can hold (COUNCIL-ADMIN.md section 3.1). What each may do is in {@link Permissions}. */
public enum Role {

    /** Msika staff. Manages councils. Belongs to no council. */
    MSIKA_OPERATOR(false, false),
    /** Runs the council's side: users, policies, rules. */
    COUNCIL_ADMIN(true, false),
    /** In charge of the markets named in the grant, and no others. */
    MARKET_MASTER(true, true),
    /** The council's revenue section. */
    REVENUE_OFFICER(true, false),
    /** Inspects the markets named in the grant. Never handles the money from what they find. */
    ENFORCEMENT_OFFICER(true, true),
    /** Any other council staff: reads, changes nothing. */
    COUNCIL_OFFICER(true, false),
    /** Reads everything in the council, the audit log included, and changes nothing. */
    AUDITOR(true, false);

    private final boolean belongsToCouncil;
    private final boolean marketScoped;

    Role(boolean belongsToCouncil, boolean marketScoped) {
        this.belongsToCouncil = belongsToCouncil;
        this.marketScoped = marketScoped;
    }

    /** True for the roles that belong to one council. False only for the operator. */
    public boolean belongsToCouncil() {
        return belongsToCouncil;
    }

    /** True if the role is held for named markets. */
    public boolean marketScoped() {
        return marketScoped;
    }

    /**
     * True for every role but the operator and the admin, until each endpoint checks its own permission: a new role
     * must never gain a right by default.
     */
    public boolean isReadOnly() {
        return this != MSIKA_OPERATOR && this != COUNCIL_ADMIN;
    }
}
