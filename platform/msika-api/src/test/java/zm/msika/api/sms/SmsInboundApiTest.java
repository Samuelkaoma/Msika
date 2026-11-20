package zm.msika.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import zm.msika.api.ApiTest;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.core.sms.SmsGatewayException;
import zm.msika.core.sms.SmsGatewayPort;
import zm.msika.core.sms.SmsTemplate;

/** The inbound webhook, processing, rate limit and outbox (card M-18), through HTTP and the real database. */
class SmsInboundApiTest extends ApiTest {

    /** A gateway that can be told to refuse the next few messages. */
    static final class FlakyGateway implements SmsGatewayPort {
        record Sent(String id, String to, String body) {}

        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger refuseNext = new AtomicInteger();

        @Override
        public String send(String messageId, String toE164, String body) {
            if (refuseNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new SmsGatewayException("scripted refusal");
            }
            sent.add(new Sent(messageId, toE164, body));
            return "gw-" + messageId;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FlakyGateway flakyGateway() {
            return new FlakyGateway();
        }
    }

    record AgentBody(UUID id) {}

    record RegisterAgentRequest(String phone, String displayName) {}

    private record Reply(int status, String body) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    FlakyGateway gateway;

    @Autowired
    OutboxWorker outbox;

    @Autowired
    SmsProcessor processor;

    @Autowired
    Clock clock;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("smsGatewayVerifier")
    CallbackVerifier smsVerifier;

    @BeforeEach
    void reset() {
        gateway.refuseNext.set(0);
    }

    private static String newPhone() {
        return "+260977" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private static String json(String id, String from, String body) {
        return "{\"gatewayMessageId\":\"" + id + "\",\"from\":\"" + from + "\",\"body\":\"" + body
                + "\",\"receivedAt\":\"2026-11-23T17:42:10+02:00\"}";
    }

    private Reply post(String json, String timestamp, String signature) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sms/inbound"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (timestamp != null) {
                b.header(SmsInboundController.TIMESTAMP_HEADER, timestamp);
            }
            if (signature != null) {
                b.header(SmsInboundController.SIGNATURE_HEADER, signature);
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Posts as the gateway: signed with the gateway secret and the current time. */
    private Reply send(String id, String from, String body) {
        String json = json(id, from, body);
        long now = clock.instant().getEpochSecond();
        return post(json, Long.toString(now), smsVerifier.sign(now, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void waitUntil(BooleanSupplier condition) {
        Instant end = Instant.now().plusSeconds(20);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("condition not met within 20 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private List<Map<String, Object>> inboundFrom(String phone) {
        return ownerJdbc().sql("select gateway_message_id, council_id, status, outcome from sms_inbound "
                        + "where from_msisdn = :p order by stored_at").param("p", phone).query().listOfRows();
    }

    private List<Map<String, Object>> outboxTo(String phone) {
        return ownerJdbc().sql("select id, template, body, status, attempts, council_id from sms_outbound "
                        + "where to_msisdn = :p order by created_at").param("p", phone).query().listOfRows();
    }

    private void processedAll(String phone, int count) {
        waitUntil(() -> inboundFrom(phone).size() == count
                && inboundFrom(phone).stream().noneMatch(r -> "RECEIVED".equals(r.get("status"))));
    }

    private UUID registerAgent(Fixture council, String phone) {
        return api().post(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/agents",
                new RegisterAgentRequest(phone, "Test Agent"), AgentBody.class).body().id();
    }

    @Test
    @DisplayName("a text from an unknown number is stored with no council, answered with the registration hint, and sent")
    void unknownSender() {
        String phone = newPhone();
        String id = "gw-" + UUID.randomUUID();

        Reply r = send(id, phone.replace("+260", "0"), "DEP B114 450");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"stored\":true");
        processedAll(phone, 1);
        assertThat(inboundFrom(phone).get(0)).containsEntry("council_id", null).containsEntry("outcome", "NOT_REGISTERED");
        List<Map<String, Object>> replies = outboxTo(phone);
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0)).containsEntry("template", "NOT_REGISTERED").containsEntry("status", "PENDING");

        outbox.sendDue();

        assertThat(outboxTo(phone).get(0)).containsEntry("status", "SENT");
        assertThat(gateway.sent).anyMatch(s -> s.to().equals(phone) && s.body().equals(SmsTemplate.NOT_REGISTERED.text()));
    }

    @Test
    @DisplayName("a repeated gatewayMessageId is stored and processed once")
    void duplicate() {
        String phone = newPhone();
        String id = "gw-" + UUID.randomUUID();

        Reply first = send(id, phone, "HELLO");
        Reply second = send(id, phone, "HELLO");
        processedAll(phone, 1);
        Reply third = send(id, phone, "HELLO");

        assertThat(first.body()).contains("\"stored\":true");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).contains("\"stored\":false").contains("\"duplicate\":true");
        assertThat(third.body()).contains("\"duplicate\":true");
        assertThat(inboundFrom(phone)).hasSize(1);
        assertThat(outboxTo(phone)).hasSize(1);
    }

    @Test
    @DisplayName("a bad, missing or old signature gets 401 and nothing is stored")
    void signatures() {
        String phone = newPhone();
        String json = json("gw-" + UUID.randomUUID(), phone, "HELP");
        long now = clock.instant().getEpochSecond();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        assertThat(post(json, null, null).status()).isEqualTo(401);
        assertThat(post(json, Long.toString(now), "sha256=" + "0".repeat(64)).status()).isEqualTo(401);
        assertThat(post(json, Long.toString(now), new CallbackVerifier("some-other-secret", clock, Duration.ofMinutes(5))
                .sign(now, bytes)).status()).isEqualTo(401);
        long old = now - 600;
        assertThat(post(json, Long.toString(old), smsVerifier.sign(old, bytes)).status()).isEqualTo(401);
        // Signed for a different body.
        assertThat(post(json.replace("HELP", "DEP B1 50000"), Long.toString(now), smsVerifier.sign(now, bytes)).status())
                .isEqualTo(401);
        assertThat(inboundFrom(phone)).isEmpty();
    }

    @Test
    @DisplayName("a message that is not shaped right gets 400")
    void badShape() {
        assertThat(send("gw-" + UUID.randomUUID(), "+44 20 7946 0000", "HELP").status()).isEqualTo(400);
        assertThat(send("", newPhone(), "HELP").status()).isEqualTo(400);
        String json = "{\"gatewayMessageId\":\"x\",\"from\":\"" + newPhone() + "\",\"receivedAt\":\"2026-11-23T17:42:10+02:00\"}";
        long now = clock.instant().getEpochSecond();
        assertThat(post(json, Long.toString(now), smsVerifier.sign(now, json.getBytes(StandardCharsets.UTF_8))).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a registered agent is answered in their council: HELP, a trader-only command, and a command for M-19")
    void knownAgent() {
        Fixture council = newCouncil();
        String phone = newPhone();
        registerAgent(council, phone);

        send("gw-" + UUID.randomUUID(), phone, "help");
        processedAll(phone, 1);
        send("gw-" + UUID.randomUUID(), phone, "SALE 120");
        processedAll(phone, 2);
        send("gw-" + UUID.randomUUID(), phone, "DEP B114 450");
        processedAll(phone, 3);

        assertThat(inboundFrom(phone)).allMatch(r -> council.councilId().equals(r.get("council_id")));
        assertThat(inboundFrom(phone)).extracting(r -> r.get("outcome")).containsExactly("HELP", "TRADERS_ONLY", "NOT_WIRED");
        assertThat(outboxTo(phone)).extracting(r -> r.get("template")).containsExactly("HELP_AGENT", "TRADERS_ONLY");
        assertThat(outboxTo(phone)).allMatch(r -> council.councilId().equals(r.get("council_id")));
    }

    @Test
    @DisplayName("more than 20 texts from one number in 10 minutes are stored but not answered")
    void rateLimit() {
        String phone = newPhone();
        for (int i = 0; i < 23; i++) {
            send("gw-" + UUID.randomUUID(), phone, "HELLO " + i);
        }
        processedAll(phone, 23);

        assertThat(inboundFrom(phone)).filteredOn(r -> "RATE_LIMITED".equals(r.get("status"))).hasSize(3);
        assertThat(outboxTo(phone)).hasSize(20);
    }

    @Test
    @DisplayName("a refused send is retried after a wait, and marked FAILED after the last try")
    void outboxRetries() {
        String phone = newPhone();
        send("gw-" + UUID.randomUUID(), phone, "HELLO");
        processedAll(phone, 1);

        gateway.refuseNext.set(2);
        outbox.sendDue();
        assertThat(outboxTo(phone).get(0)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        // Not due yet: the wait after the first refusal has not passed.
        outbox.sendDue();
        assertThat(outboxTo(phone).get(0)).containsEntry("attempts", 1);

        makeDue(phone);
        outbox.sendDue();
        makeDue(phone);
        outbox.sendDue();
        assertThat(outboxTo(phone).get(0)).containsEntry("status", "SENT").containsEntry("attempts", 3);
        assertThat(gateway.sent).filteredOn(s -> s.to().equals(phone)).hasSize(1);

        String other = newPhone();
        send("gw-" + UUID.randomUUID(), other, "HELLO");
        processedAll(other, 1);
        gateway.refuseNext.set(100);
        for (int i = 0; i <= OutboxWorker.BACKOFF.size(); i++) {
            makeDue(other);
            outbox.sendDue();
        }
        assertThat(outboxTo(other).get(0)).containsEntry("status", "FAILED")
                .containsEntry("attempts", OutboxWorker.BACKOFF.size() + 1);
        makeDue(other);
        outbox.sendDue();
        assertThat(outboxTo(other).get(0)).containsEntry("attempts", OutboxWorker.BACKOFF.size() + 1);
    }

    @Test
    @DisplayName("a text left unprocessed (say, by a crash) is picked up by the sweeper")
    void sweeper() {
        String phone = newPhone();
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("insert into sms_inbound (id, gateway_message_id, from_msisdn, body, received_at, stored_at, status) "
                        + "values (:id, :g, :p, 'HELLO', now(), now() - interval '1 minute', 'RECEIVED')")
                .param("id", id).param("g", "gw-" + id).param("p", phone).update();

        processor.sweep(Duration.ofSeconds(15));

        assertThat(inboundFrom(phone).get(0)).containsEntry("status", "PROCESSED");
        assertThat(outboxTo(phone)).hasSize(1);
    }

    /** Makes the phone's waiting messages due now, instead of waiting for the back-off. Test only. */
    private void makeDue(String phone) {
        ownerJdbc().sql("update sms_outbound set next_attempt_at = now() - interval '1 second' where to_msisdn = :p")
                .param("p", phone).update();
    }
}
