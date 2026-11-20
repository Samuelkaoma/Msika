package zm.msika.core.payments;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import zm.msika.ledger.Money;

/**
 * What Msika needs from a payments system (ARCHITECTURE.md section 1). Today the adapter talks to
 * {@code zamsim}; later it talks to Zamtel Money, by changing configuration and not this interface.
 *
 * <p>Everything about a payments system is unreliable: it can be slow, fail, answer twice or answer out of
 * order. So every call carries an idempotency key, and the real outcome is learned from the system's
 * callback (see {@link HandlePaymentEvent}), never assumed from the reply to a request.
 *
 * <p>Wallets are named by the payments system's own opaque ids, which Msika stores and never interprets.
 */
public interface PaymentsPort {

    /**
     * An agent puts a customer's cash into the customer's wallet.
     *
     * <p>Sending the same {@code idempotencyKey} again returns the same transaction and moves money once,
     * so a request that timed out can safely be sent again.
     *
     * @param idempotencyKey unique per request; Msika uses one derived from its own payment request
     * @param agentWalletId the agent's wallet, which the cash is paid out of
     * @param customerWalletId the trader's wallet, which is credited
     * @param amount the amount, in ZMW
     * @param reference echoed back in the callback, so Msika can match the outcome to its request
     * @return the payments system's id for the transaction
     * @throws PaymentsUnavailableException if the system could not be reached or answered with an error
     */
    String cashIn(String idempotencyKey, String agentWalletId, String customerWalletId, Money amount, String reference);

    /**
     * Looks up one wallet: its kind and the phone number it is registered to. Used to check that a wallet a
     * council registers really belongs to that trader or agent.
     *
     * @return the wallet, or empty if the payments system has no wallet with that id
     * @throws PaymentsUnavailableException if the system could not be reached or answered with an error
     */
    Optional<PaymentsWallet> findWallet(String walletId);

    /**
     * The settled movements on one wallet between two dates, both included, by the date the money moved in
     * Lusaka. Used by the nightly reconciliation (card M-16) to compare the payments system's record with
     * Msika's ledger.
     *
     * @throws PaymentsUnavailableException if the system could not be reached or answered with an error
     */
    List<StatementLine> statement(String walletId, LocalDate from, LocalDate to);
}
