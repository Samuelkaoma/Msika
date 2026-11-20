package zm.msika.core.payments;

/**
 * The payments system could not be reached, or refused the request. The request may or may not have been
 * received, which is why every request carries an idempotency key: sending it again is always safe.
 */
public class PaymentsUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PaymentsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentsUnavailableException(String message) {
        super(message);
    }
}
