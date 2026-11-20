package zm.msika.api.sms;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import zm.msika.core.sms.SmsCommand;
import zm.msika.core.sms.SmsRouting;
import zm.msika.core.sms.SmsTemplate;

/**
 * Runs a command that passed the routing checks: the use case behind each command in {@code docs/SMS-PROTOCOL.md}
 * (card M-19). It is called inside the transaction that processes the message, as the sender's council, so what it
 * records and the replies it returns are saved together or not at all.
 *
 * <p>Anything that calls another system (the payments system, to send a deposit or read a balance) must not happen
 * inside that transaction. The handler returns it as a {@link Later} instead: it runs after the transaction has
 * committed, and the replies it returns are queued then.
 */
public interface SmsCommandHandler {

    /** A text to send because of the command: to the sender, or to someone else (the trader, for a deposit). */
    record Reply(String toMsisdn, SmsTemplate template, String body) {

        /** A reply rendered from a template. */
        public static Reply of(String toMsisdn, SmsTemplate template, String... namesAndValues) {
            return new Reply(toMsisdn, template, template.render(namesAndValues));
        }
    }

    /** Work that must run after the processing transaction has committed, outside any transaction. */
    @FunctionalInterface
    interface Later {
        /** @return the replies to queue once it has run */
        List<Reply> run();
    }

    /** What came of the command. */
    record Result(String outcome, List<Reply> replies, List<Later> later) {

        public Result(String outcome, List<Reply> replies) {
            this(outcome, replies, List.of());
        }

        public static Result reply(String outcome, Reply reply) {
            return new Result(outcome, List.of(reply));
        }
    }

    /**
     * @param inboundId Msika's id for the received text: stored once, so it serves as the idempotency key of
     *     anything the command records
     * @param sender the sender's record, or null for a {@code REG} from a number nobody has registered
     * @param fromMsisdn the sender's number, E.164
     * @param receivedAt when the gateway received the text
     */
    Result handle(UUID inboundId, SmsRouting.Sender sender, String fromMsisdn, SmsCommand command, Instant receivedAt);
}
