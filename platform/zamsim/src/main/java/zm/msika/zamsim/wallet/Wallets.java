package zm.msika.zamsim.wallet;

import java.time.Instant;
import java.util.UUID;

/** The plain data zamsim works with. Amounts are whole ngwee in a {@code long}. */
public final class Wallets {

    private Wallets() {}

    /** Who a wallet belongs to. There is exactly one ISSUER, created by the migration. */
    public enum WalletType {
        ISSUER, TRADER, AGENT, MERCHANT, COUNCIL, LENDER, SUPPLIER
    }

    public enum TransactionKind {
        /** An agent puts a customer's cash into the customer's wallet, out of the agent's float. */
        CASH_IN,
        /** Wallet to wallet. */
        TRANSFER,
        /** Money enters the system: ISSUER to a wallet. (Cash handed over, in real life.) */
        FUND,
        /** Money leaves the system: a wallet to ISSUER. */
        WITHDRAW
    }

    public enum TransactionStatus {
        SUCCEEDED, FAILED
    }

    public record Wallet(UUID id, WalletType type, String name, String msisdn, long balanceNgwee, Instant createdAt) {}

    /**
     * One money movement and its outcome. A transaction that failed (for example for lack of money) is
     * still a transaction: it is recorded, moves nothing, and says why.
     */
    public record Transaction(
            UUID id,
            TransactionKind kind,
            TransactionStatus status,
            UUID fromWalletId,
            UUID toWalletId,
            long amountNgwee,
            String reference,
            String failureReason,
            Instant createdAt) {}

    /** One line of a wallet's statement: a signed amount (credit positive) and the balance after it. */
    public record StatementLine(
            long lineId,
            UUID transactionId,
            TransactionKind kind,
            long amountNgwee,
            long balanceAfterNgwee,
            UUID counterpartyWalletId,
            String reference,
            Instant at) {}

    /** The one ISSUER wallet's id, fixed by the migration. */
    public static final UUID ISSUER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
}
