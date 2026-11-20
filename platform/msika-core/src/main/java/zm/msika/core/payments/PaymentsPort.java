package zm.msika.core.payments;

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
}
