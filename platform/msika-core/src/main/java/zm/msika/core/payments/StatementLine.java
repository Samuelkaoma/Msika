package zm.msika.core.payments;

import java.time.Instant;
import java.util.Objects;

/**
 * One settled movement on a wallet, as the payments system's statement shows it. Only settled money appears on
 * a statement: a failed or still-pending transaction has no line.
 *
 * @param transactionId the payments system's id for the transaction
 * @param kind what sort of transaction it was (CASH_IN, TRANSFER, REQUEST_TO_PAY ...)
 * @param amountNgwee signed: positive when money came into this wallet, negative when it left
 * @param counterpartyWalletId the wallet on the other side, or null if there is none
 * @param reference what the requester put on the transaction, or null
 * @param at when the money moved
 */
public record StatementLine(
        String transactionId,
        String kind,
        long amountNgwee,
        String counterpartyWalletId,
        String reference,
        Instant at) {

    public StatementLine {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }
}
