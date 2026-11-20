package zm.msika.api.sms;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.sms.SmsGatewayPort;

/**
 * The gateway for development and demos (ARCHITECTURE.md section 7): nothing leaves the machine. Sent messages are
 * kept in memory, newest last, for the sim console's fake phones (card M-20) to show. A message sent twice (a retry)
 * is kept once.
 */
public class ConsoleSmsGateway implements SmsGatewayPort {

    /** A message as a fake phone receives it. */
    public record Delivered(String messageId, String toE164, String body, Instant at) {}

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleSmsGateway.class);
    private static final int KEEP = 2000;

    private final Deque<Delivered> delivered = new ArrayDeque<>();
    private final Clock clock;

    public ConsoleSmsGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String send(String messageId, String toE164, String body) {
        boolean repeat = delivered.stream().anyMatch(d -> d.messageId().equals(messageId));
        if (!repeat) {
            delivered.addLast(new Delivered(messageId, toE164, body, clock.instant()));
            while (delivered.size() > KEEP) {
                delivered.removeFirst();
            }
            LOG.info("console SMS to {}: {}", new PhoneNumber(toE164), body);
        }
        return "console-" + messageId;
    }

    /** What one phone has received, oldest first. */
    public synchronized List<Delivered> deliveredTo(String toE164) {
        return delivered.stream().filter(d -> d.toE164().equals(toE164)).toList();
    }

    /** Everything kept, oldest first. */
    public synchronized List<Delivered> all() {
        return List.copyOf(delivered);
    }
}
