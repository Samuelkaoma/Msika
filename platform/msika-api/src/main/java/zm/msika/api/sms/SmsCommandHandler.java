package zm.msika.api.sms;

import java.time.Instant;
import java.util.List;
import zm.msika.core.sms.SmsCommand;
import zm.msika.core.sms.SmsRouting;
import zm.msika.core.sms.SmsTemplate;

/**
 * Runs a command that passed the routing checks: the use case behind each command in {@code docs/SMS-PROTOCOL.md}
 * (card M-19). It is called inside the transaction that processes the message, as the sender's council, so what it
 * records and the replies it returns are saved together or not at all.
 */
public interface SmsCommandHandler {

    /** A text to send because of the command: to the sender, or to someone else (the trader, for a deposit). */
    record Reply(String toMsisdn, SmsTemplate template, String body) {

        /** A reply rendered from a template. */
        public static Reply of(String toMsisdn, SmsTemplate template, String... namesAndValues) {
            return new Reply(toMsisdn, template, template.render(namesAndValues));
        }
    }

    /** What came of the command. */
    record Result(String outcome, List<Reply> replies) {}

    /**
     * @param sender the sender's record, or null for a {@code REG} from a number nobody has registered
     * @param fromMsisdn the sender's number, E.164
     * @param receivedAt when the gateway received the text
     */
    Result handle(SmsRouting.Sender sender, String fromMsisdn, SmsCommand command, Instant receivedAt);
}
