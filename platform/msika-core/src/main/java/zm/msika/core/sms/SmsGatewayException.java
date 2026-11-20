package zm.msika.core.sms;

/** The SMS gateway did not take a message. The outbox tries again later. */
public class SmsGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SmsGatewayException(String message) {
        super(message);
    }

    public SmsGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
