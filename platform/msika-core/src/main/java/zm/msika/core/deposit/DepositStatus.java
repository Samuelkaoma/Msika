package zm.msika.core.deposit;

/**
 * Where a deposit is in its life (ARCHITECTURE.md section 4.1).
 *
 * <pre>
 *   PENDING --payment confirmed--> CONFIRMED --trader says NO--> DISPUTED
 *      |  \--payment failed------> FAILED
 *      \--agent cancels---------> CANCELLED --payment confirmed anyway--> DISPUTED
 * </pre>
 *
 * FAILED and CANCELLED never put anything on the ledger. DISPUTED is a flag for the council to look at: it
 * does not undo anything by itself.
 */
public enum DepositStatus {
    /** The payments system has been asked; its answer has not come. */
    PENDING,
    /** The payments system confirmed, and the DEPOSITED entry is on the ledger. */
    CONFIRMED,
    /** The payments system said it did not happen. Nothing is on the ledger. */
    FAILED,
    /** The agent cancelled before the answer came. */
    CANCELLED,
    /** Someone says it is wrong (the trader), or money moved after the agent had cancelled. */
    DISPUTED;

    /** True when no further payment outcome is expected. */
    public boolean isSettled() {
        return this != PENDING;
    }
}
