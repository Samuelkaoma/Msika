package zm.msika.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import zm.msika.api.ApiTest;

/** The sim console's fake phones (card M-20): operators only, and texts go through the normal path. */
@TestPropertySource(properties = "msika.sim-console.enabled=true")
class SimConsoleApiTest extends ApiTest {

    record Phone(String phone, String name, String kind, UUID councilId, String council, List<String> stalls) {}

    record Message(String direction, String body, Instant at, String status, String template) {}

    record TextRequest(String from, String body) {}

    record AgentBody(UUID id) {}

    record RegisterAgentRequest(String phone, String displayName) {}

    @Test
    @DisplayName("an operator lists the phones, texts as an agent, and sees the reply in the conversation")
    void fakePhones() throws InterruptedException {
        Fixture council = newCouncil();
        String phone = "+260977" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/agents",
                new RegisterAgentRequest(phone, "Console Agent"), AgentBody.class);

        Phone[] phones = api().get(operatorToken(), "/api/v1/sim/phones", Phone[].class).body();
        assertThat(phones).anyMatch(p -> p.phone().equals(phone) && p.kind().equals("AGENT")
                && p.councilId().equals(council.councilId()) && p.name().equals("Console Agent"));

        var sent = api().post(operatorToken(), "/api/v1/sim/sms", new TextRequest(phone.replace("+260", "0"), "help"),
                String.class);
        assertThat(sent.status()).isEqualTo(200);

        Message[] conversation = new Message[0];
        Instant end = Instant.now().plusSeconds(20);
        while (conversation.length < 2 && Instant.now().isBefore(end)) {
            Thread.sleep(50);
            conversation = api().get(operatorToken(), "/api/v1/sim/sms?phone=" + phone, Message[].class).body();
        }
        assertThat(conversation).extracting(Message::direction).containsExactly("IN", "OUT");
        assertThat(conversation[0].body()).isEqualTo("help");
        assertThat(conversation[1].template()).isEqualTo("HELP_AGENT");
    }

    @Test
    @DisplayName("council users cannot use the fake phones")
    void operatorsOnly() {
        Fixture council = newCouncil();
        assertThat(api().get(council.adminToken(), "/api/v1/sim/phones", String.class).status()).isEqualTo(403);
        assertThat(api().post(council.adminToken(), "/api/v1/sim/sms", new TextRequest("+260977000001", "HELP"),
                String.class).status()).isEqualTo(403);
        assertThat(api().get(null, "/api/v1/sim/phones", String.class).status()).isEqualTo(401);
    }
}
