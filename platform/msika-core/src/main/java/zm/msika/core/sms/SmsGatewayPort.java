package zm.msika.core.sms;

/**
 * What Msika needs from whatever carries its text messages to phones (AGENTS.md rule 6). Today that is the sim
 * console (dev and demos); in the pilot a relay holding a SIM (card M-21); later a shortcode or SMPP provider.
 *
 * <p>Sending is at least once: the outbox worker calls {@link #send} again if it does not hear back, so the
 * gateway is given Msika's own message id and should use it to drop a repeat.
 */
public interface SmsGatewayPort {

    /**
     * Hands one message to the gateway.
     *
     * @param messageId Msika's id for the message, the same on every retry
     * @param toE164 the phone number, like {@code +260977000001}
     * @param body at most 160 GSM-7 characters
     * @return the gateway's own reference for the message, or null if it gives none
     * @throws SmsGatewayException if the gateway did not take the message; it will be retried
     */
    String send(String messageId, String toE164, String body);

    /**
     * True if this gateway only queues messages for a relay to collect (card M-21) and reports the outcome later.
     * The outbox worker then leaves the messages for the relay instead of sending them itself.
     */
    default boolean collectedByRelay() {
        return false;
    }
}
