package zm.msika.ledger;

import java.util.Optional;

/**
 * What a ledger entry records, from ARCHITECTURE.md section 5.
 *
 * <p>Each kind says which {@link Provenance} it may carry, so a declared sale can never be recorded
 * as verified. {@link #REVERSAL} has no fixed provenance: it cancels an earlier entry and carries
 * that entry's provenance. {@link LedgerEntry#reversalOf} builds it that way, and the
 * {@code RecordEntry} use case checks it against the stored original.
 */
public enum EntryKind {

    /** Cash deposited through an agent and confirmed by the payments system. */
    DEPOSIT(Provenance.DEPOSITED),

    /** A customer paid the trader's merchant wallet. */
    MERCHANT_SALE(Provenance.VERIFIED),

    /** The trader paid a supplier from their wallet. */
    SUPPLIER_PAYMENT(Provenance.VERIFIED),

    /** A cash sale the trader reported by SMS that nothing has confirmed. */
    DECLARED_SALE(Provenance.DECLARED),

    /** Council levy paid from the trader's wallet. */
    LEVY_PAYMENT(Provenance.VERIFIED),

    /** A share of a deposit used to repay a stock loan. */
    LOAN_REPAYMENT(Provenance.VERIFIED),

    /** Cancels an earlier entry. The original is never edited or deleted (AGENTS.md rule 2). */
    REVERSAL(null);

    private final Provenance requiredProvenance;

    EntryKind(Provenance requiredProvenance) {
        this.requiredProvenance = requiredProvenance;
    }

    /** The only provenance this kind may carry, or empty for {@link #REVERSAL}. */
    public Optional<Provenance> requiredProvenance() {
        return Optional.ofNullable(requiredProvenance);
    }
}
