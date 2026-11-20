package zm.msika.core.deposit;

/**
 * Told, in the same transaction, when a deposit reaches an outcome a person should hear about (card M-19): the
 * trader when money is confirmed into their wallet or a deposit for them is cancelled, the agent when a deposit
 * fails. The SMS channel implements it by queueing texts, so a text exists exactly when the change was saved.
 */
public interface DepositEvents {

    /** Nobody is told. */
    DepositEvents NONE = new DepositEvents() {};

    /** The money is in the trader's wallet and on the ledger. Also called when it was paid after a cancel. */
    default void confirmed(Deposit deposit) {}

    /** The payments system did not move the money. */
    default void failed(Deposit deposit, String reason) {}

    /** The agent cancelled before the money was confirmed. */
    default void cancelled(Deposit deposit) {}
}
