package zm.msika.core.reconciliation;

import java.util.Objects;
import java.util.UUID;
import zm.msika.ledger.EntryKind;

/**
 * One way in which Msika's ledger and the payments system disagree about a day (card M-16).
 *
 * @param kind which side is missing it, or that the amounts differ
 * @param paymentsTransactionId the payments system's transaction id the difference is about
 * @param entryKind what the money is (a deposit, a sale, a supplier payment), from whichever side has it
 * @param stallId the stall it belongs to, from whichever side knows it
 * @param ledgerEntryId Msika's entry, or null when Msika has none
 * @param msikaAmountNgwee the amount on Msika's ledger, or null when Msika has none
 * @param paymentsAmountNgwee the amount the payments system moved, or null when it has no such movement
 */
public record Difference(
        Kind kind,
        String paymentsTransactionId,
        EntryKind entryKind,
        UUID stallId,
        Long ledgerEntryId,
        Long msikaAmountNgwee,
        Long paymentsAmountNgwee) {

    public enum Kind {
        /** The payments system moved money that Msika should have recorded and did not. */
        MISSING_IN_MSIKA,
        /** Msika recorded a payment the payments system has no settled movement for. */
        MISSING_IN_PAYMENTS,
        /** Both sides have it, with different amounts. */
        AMOUNT_DIFFERS
    }

    public Difference {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(paymentsTransactionId, "paymentsTransactionId must not be null");
        Objects.requireNonNull(entryKind, "entryKind must not be null");
        if ((kind == Kind.MISSING_IN_MSIKA) != (msikaAmountNgwee == null)) {
            throw new IllegalArgumentException("a Msika amount is given exactly when Msika has the entry");
        }
        if ((kind == Kind.MISSING_IN_PAYMENTS) != (paymentsAmountNgwee == null)) {
            throw new IllegalArgumentException("a payments amount is given exactly when the payments system has it");
        }
    }
}
