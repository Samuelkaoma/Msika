package zm.msika.ledger;

/**
 * How sure we are about a ledger entry (AGENTS.md rule 3).
 *
 * <p>Figures shown to anyone, statements and the credit score keep these three apart. A declared
 * figure never becomes verified by being repeated or added to a verified one.
 */
public enum Provenance {

    /** A digital payment the payments system confirmed, such as a merchant sale. */
    VERIFIED,

    /** Cash the trader deposited through an agent, confirmed by the payments system. */
    DEPOSITED,

    /** Something the trader told us by SMS that nothing has confirmed. */
    DECLARED
}
