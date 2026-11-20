package zm.msika.core.user;

/**
 * What a signed-in user may do (ARCHITECTURE.md section 9). Every endpoint is denied unless a rule
 * allows the user's role.
 */
public enum Role {

    /** Msika staff. Manages councils. Belongs to no council. */
    MSIKA_OPERATOR(false),

    /** A council's administrator. May read and change everything in their own council. */
    COUNCIL_ADMIN(true),

    /** A council officer. May read their own council's data and change nothing. */
    COUNCIL_OFFICER(true);

    private final boolean belongsToCouncil;

    Role(boolean belongsToCouncil) {
        this.belongsToCouncil = belongsToCouncil;
    }

    /** True for the roles that belong to one council. False only for the operator. */
    public boolean belongsToCouncil() {
        return belongsToCouncil;
    }

    /** True if this role may only read. */
    public boolean isReadOnly() {
        return this == COUNCIL_OFFICER;
    }
}
