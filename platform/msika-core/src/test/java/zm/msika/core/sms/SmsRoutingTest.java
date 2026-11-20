package zm.msika.core.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.sms.SmsRouting.Decision;
import zm.msika.core.sms.SmsRouting.Sender;

/** Who may send what, and what a mistake gets back (card M-18). */
class SmsRoutingTest {

    private final SmsCommandParser parser = new SmsCommandParser();
    private final UUID council = UUID.randomUUID();
    private final Sender trader = new Sender(council, UUID.randomUUID(), null);
    private final Sender agent = new Sender(council, null, UUID.randomUUID());

    private Decision decide(List<Sender> matches, String body) {
        return SmsRouting.decide(matches, parser.parse(body), SmsCommandParser.DEFAULT_MAX_NGWEE);
    }

    private SmsTemplate answer(List<Sender> matches, String body) {
        Decision d = decide(matches, body);
        assertThat(d).as(body).isInstanceOf(Decision.Answer.class);
        return ((Decision.Answer) d).template();
    }

    @Test
    @DisplayName("an unknown number only hears how to register, unless it sends REG")
    void unknownSender() {
        assertThat(answer(List.of(), "DEP B114 450")).isEqualTo(SmsTemplate.NOT_REGISTERED);
        assertThat(answer(List.of(), "HELP")).isEqualTo(SmsTemplate.NOT_REGISTERED);
        assertThat(answer(List.of(), "hello")).isEqualTo(SmsTemplate.NOT_REGISTERED);
        assertThat(answer(List.of(), "REG")).isEqualTo(SmsTemplate.USAGE_REG);
        assertThat(decide(List.of(), "REG SOW-B-114"))
                .isEqualTo(new Decision.Handle(null, new SmsCommand.Register("SOW-B-114")));
        assertThat(((Decision.Answer) decide(List.of(), "BAL")).councilId()).isNull();
    }

    @Test
    @DisplayName("a number registered with two councils is asked to call the market office")
    void ambiguous() {
        Sender elsewhere = new Sender(UUID.randomUUID(), UUID.randomUUID(), null);
        assertThat(answer(List.of(trader, elsewhere), "BAL")).isEqualTo(SmsTemplate.SENDER_AMBIGUOUS);
    }

    @Test
    @DisplayName("agents cannot use trader commands and traders cannot use agent commands")
    void audiences() {
        Decision d = decide(List.of(trader), "DEP B114 450");
        assertThat(d).isEqualTo(new Decision.Answer(council, SmsTemplate.AGENTS_ONLY,
                java.util.Map.of("COMMAND", "DEP"), "AGENTS_ONLY"));
        assertThat(answer(List.of(agent), "SALE 120")).isEqualTo(SmsTemplate.TRADERS_ONLY);
        assertThat(answer(List.of(trader), "ROUND")).isEqualTo(SmsTemplate.AGENTS_ONLY);
        assertThat(decide(List.of(agent), "DEP B114 450"))
                .isEqualTo(new Decision.Handle(agent, new SmsCommand.Deposit("B114", 45_000)));
        assertThat(decide(List.of(trader), "SALE 120"))
                .isEqualTo(new Decision.Handle(trader, new SmsCommand.DeclareSale(12_000)));
        Sender both = new Sender(council, UUID.randomUUID(), UUID.randomUUID());
        assertThat(decide(List.of(both), "DEP B114 450")).isInstanceOf(Decision.Handle.class);
        assertThat(decide(List.of(both), "SALE 120")).isInstanceOf(Decision.Handle.class);
    }

    @Test
    @DisplayName("HELP gives each kind of user their own list")
    void help() {
        assertThat(answer(List.of(trader), "HELP")).isEqualTo(SmsTemplate.HELP_TRADER);
        assertThat(answer(List.of(agent), "help")).isEqualTo(SmsTemplate.HELP_AGENT);
    }

    @Test
    @DisplayName("each kind of mistake gets the reply that says how to fix it")
    void mistakes() {
        assertThat(answer(List.of(trader), "hello")).isEqualTo(SmsTemplate.UNKNOWN_COMMAND);
        assertThat(answer(List.of(trader), "  ")).isEqualTo(SmsTemplate.UNKNOWN_COMMAND);
        assertThat(answer(List.of(agent), "DEP B114")).isEqualTo(SmsTemplate.USAGE_DEP);
        assertThat(answer(List.of(agent), "DEP B114 450 500")).isEqualTo(SmsTemplate.USAGE_DEP);
        assertThat(answer(List.of(trader), "SALE")).isEqualTo(SmsTemplate.USAGE_SALE);
        assertThat(answer(List.of(trader), "NO")).isEqualTo(SmsTemplate.USAGE_NO);
        assertThat(answer(List.of(agent), "UNDO")).isEqualTo(SmsTemplate.USAGE_UNDO);
        assertThat(answer(List.of(trader), "LOAN 800")).isEqualTo(SmsTemplate.USAGE_LOAN);
        assertThat(decide(List.of(trader), "TODAY please"))
                .isEqualTo(new Decision.Answer(council, SmsTemplate.USAGE_PLAIN, java.util.Map.of("COMMAND", "TODAY"), "EXTRA_WORDS"));
        assertThat(answer(List.of(trader), "SALE abc")).isEqualTo(SmsTemplate.INVALID_AMOUNT);
        assertThat(answer(List.of(trader), "SALE 0")).isEqualTo(SmsTemplate.AMOUNT_NOT_POSITIVE);
        assertThat(decide(List.of(agent), "DEP B114 99999"))
                .isEqualTo(new Decision.Answer(council, SmsTemplate.AMOUNT_TOO_LARGE, java.util.Map.of("AMOUNT", "K50,000"),
                        "AMOUNT_TOO_LARGE"));
        assertThat(answer(List.of(agent), "DEP B/1 450")).isEqualTo(SmsTemplate.INVALID_STALL);
        assertThat(answer(List.of(trader), "NO 0OOO")).isEqualTo(SmsTemplate.INVALID_REFERENCE);
    }

    @Test
    @DisplayName("every answer renders to one SMS")
    void answersRender() {
        for (String body : List.of("hello", "DEP B114", "TODAY please", "SALE abc", "SALE 0", "DEP B114 99999",
                "DEP B/1 450", "NO 0OOO", "HELP", "ROUND")) {
            Decision d = decide(List.of(trader), body);
            if (d instanceof Decision.Answer a) {
                assertThat(Gsm7.fitsOneSms(a.template().render(a.values()))).isTrue();
            }
        }
    }
}
