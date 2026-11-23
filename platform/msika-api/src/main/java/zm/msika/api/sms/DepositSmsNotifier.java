package zm.msika.api.sms;

import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Component;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.sms.SmsFormat;
import zm.msika.core.sms.SmsTemplate;

/**
 * Texts people when a deposit reaches an outcome (ARCHITECTURE.md section 4.1, card M-19): the trader when money is
 * confirmed into their wallet, so they can say {@code NO} if it is wrong; the agent when it failed; the trader when
 * the agent cancelled. The texts are queued in the same transaction as the change, so they exist exactly when it was
 * saved.
 */
@Component
class DepositSmsNotifier implements zm.msika.api.deposit.DepositEventsHub.Part {

    private final SmsQueries queries;
    private final SmsStore store;
    private final Clock clock;

    DepositSmsNotifier(SmsQueries queries, SmsStore store, Clock clock) {
        this.queries = queries;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void confirmed(Deposit d) {
        queries.holderPhone(d.councilId(), d.stallId()).ifPresent(phone -> {
            String agent = queries.agent(d.councilId(), d.agentId()).map(SmsQueries.AgentInfo::displayName).orElse("?");
            send(d, phone, SmsTemplate.DEPOSIT_CONFIRMED, "AMOUNT", SmsFormat.kwacha(d.amount().minorUnits()),
                    "STALL", stall(d), "NAME", agent, "REF", d.reference());
        });
    }

    @Override
    public void failed(Deposit d, String reason) {
        queries.agent(d.councilId(), d.agentId()).ifPresent(agent ->
                send(d, agent.msisdn(), SmsTemplate.DEPOSIT_FAILED, "REF", d.reference(), "REASON", plain(reason)));
    }

    @Override
    public void cancelled(Deposit d) {
        queries.holderPhone(d.councilId(), d.stallId()).ifPresent(phone ->
                send(d, phone, SmsTemplate.DEPOSIT_CANCELLED_TRADER, "REF", d.reference(),
                        "AMOUNT", SmsFormat.kwacha(d.amount().minorUnits()), "STALL", stall(d)));
    }

    private String stall(Deposit d) {
        return queries.stallCode(d.councilId(), d.stallId()).orElse("?");
    }

    private void send(Deposit d, String to, SmsTemplate template, String... values) {
        store.enqueue(d.councilId(), to, template.render(values), template.name(), null, clock.instant());
    }

    /** The payments system's failure code in words an agent understands. */
    static String plain(String reason) {
        if (reason == null) {
            return "the payment did not go through";
        }
        return switch (reason) {
            case "INSUFFICIENT_FUNDS" -> "not enough float";
            case "EXPIRED" -> "it timed out";
            case "REJECTED" -> "it was rejected";
            default -> reason.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
