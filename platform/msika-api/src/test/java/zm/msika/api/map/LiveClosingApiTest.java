package zm.msika.api.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiTest;
import zm.msika.api.payments.CallbackVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.payments.StatementLine;
import zm.msika.ledger.Money;

/** The live closing view (card M-51): deposits by SMS reach the map as server-sent events, in order. */
class LiveClosingApiTest extends ApiTest {

    static final class ScriptedPayments implements PaymentsPort {
        record Call(String key, String agent, String customer, long amount, String reference) {}

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String cashIn(String key, String agent, String customer, Money amount, String reference) {
            calls.add(new Call(key, agent, customer, amount.minorUnits(), reference));
            return "tx-" + key;
        }

        @Override
        public String transfer(String key, String from, String to, Money amount, String reference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PaymentsWallet> findWallet(String walletId) {
            return Optional.empty();
        }

        @Override
        public List<StatementLine> statement(String walletId, LocalDate from, LocalDate to) {
            return List.of();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedPayments scriptedPayments() {
            return new ScriptedPayments();
        }
    }

    record AgentBody(UUID id) {}

    record RegisterAgentRequest(String phone, String displayName) {}

    record Start(String from) {}

    record PlanBody(UUID id) {}

    record Round(List<String> aisles, List<UUID> done, List<UUID> notReached) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ScriptedPayments payments;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    Clock clock;

    @Autowired
    CallbackVerifier paymentsVerifier;

    @Autowired
    @Qualifier("smsGatewayVerifier")
    CallbackVerifier smsVerifier;

    @Autowired
    MapEvents events;

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

    private int post(String path, String json, String tsHeader, String sigHeader, CallbackVerifier v) {
        long now = clock.instant().getEpochSecond();
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json").header(tsHeader, Long.toString(now))
                    .header(sigHeader, v.sign(now, json.getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("three DEP messages give three deposit events on the map feed, in order; the agent's round shows who is left")
    void threeDepositsThreeEvents() throws Exception {
        Fixture council = newCouncil();
        UUID c = council.councilId();
        UUID market = UUID.randomUUID();
        String code = "LV" + (char) ('A' + ThreadLocalRandom.current().nextInt(26));
        String agentPhone = "+260977" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        UUID agent = api().post(council.adminToken(), "/api/v1/councils/" + c + "/agents",
                new RegisterAgentRequest(agentPhone, "Live Agent"), AgentBody.class).body().id();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<UUID> stalls = new ArrayList<>();
        tenant.inCouncil(c, () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) values (:id, :c, :code, 'Live', '20:00', now())")
                    .param("id", market).param("c", c).param("code", code).update();
            for (int i = 1; i <= 4; i++) {
                UUID stall = UUID.randomUUID();
                UUID trader = UUID.randomUUID();
                stalls.add(stall);
                jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) values (:id, :c, :m, :code, 'STANDARD', 'ALLOCATED', :t)")
                        .param("id", stall).param("c", c).param("m", market).param("code", code + "-A-" + i)
                        .param("t", UUID.randomUUID().toString().replace("-", "")).update();
                jdbc.sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :p, 'Live Trader')")
                        .param("id", trader).param("c", c).param("p", "+260966" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)))
                        .update();
                jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) values (:id, :c, :s, :t, now() - interval '1 day')")
                        .param("id", UUID.randomUUID()).param("c", c).param("s", stall).param("t", trader).update();
                jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, stall_id) values (:w, :c, 'TRADER', :s)")
                        .param("w", "w-t" + i + "-" + tag).param("c", c).param("s", stall).update();
            }
            jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, agent_id) values (:w, :c, 'AGENT', :a)")
                    .param("w", "w-agent-" + tag).param("c", c).param("a", agent).update();
        });
        UUID plan = api().post(council.adminToken(), "/api/v1/councils/" + c + "/markets/" + market + "/plans", new Start("GENERATED"),
                PlanBody.class).body().id();
        api().post(council.adminToken(), "/api/v1/councils/" + c + "/plans/" + plan + "/publish", null, String.class);

        // The map, listening.
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        var stream = HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/councils/" + c
                        + "/markets/" + market + "/map-events")).header("Authorization", "Bearer " + council.officerToken())
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        stream.thenAccept(r -> r.body().forEach(lines::add));
        waitUntil(() -> events.watchers(market) == 1);

        for (int i = 1; i <= 3; i++) {
            int before = payments.calls.size();
            String json = "{\"gatewayMessageId\":\"gw-" + UUID.randomUUID() + "\",\"from\":\"" + agentPhone + "\",\"body\":\"DEP A"
                    + i + " " + (100 * i) + "\",\"receivedAt\":\"" + Instant.now() + "\"}";
            assertThat(post("/api/v1/sms/inbound", json, "X-Msika-Timestamp", "X-Msika-Signature", smsVerifier)).isEqualTo(200);
            waitUntil(() -> payments.calls.size() > before);
            ScriptedPayments.Call call = payments.calls.get(payments.calls.size() - 1);
            String cb = "{\"callbackId\":\"cb-" + call.key() + "\",\"event\":\"transaction.completed\",\"transaction\":{\"id\":\"tx-"
                    + call.key() + "\",\"kind\":\"CASH_IN\",\"status\":\"SUCCEEDED\",\"amountNgwee\":" + call.amount()
                    + ",\"fromWalletId\":\"" + call.agent() + "\",\"toWalletId\":\"" + call.customer() + "\",\"reference\":\""
                    + call.reference() + "\",\"failureReason\":null,\"completedAt\":\"" + Instant.now() + "\"}}";
            assertThat(post("/api/v1/payments/callback", cb, "X-Timestamp", "X-Signature", paymentsVerifier)).isEqualTo(200);
        }

        waitUntil(() -> lines.stream().filter(l -> l.startsWith("event:deposit")).count() == 3);
        List<String> data = lines.stream().filter(l -> l.startsWith("data:") && l.contains("stallCode")).toList();
        assertThat(data).hasSize(3);
        assertThat(data.get(0)).contains("\"stallCode\":\"" + code + "-A-1\"").contains("\"amountNgwee\":10000");
        assertThat(data.get(1)).contains("\"stallCode\":\"" + code + "-A-2\"");
        assertThat(data.get(2)).contains("\"stallCode\":\"" + code + "-A-3\"");
        List<Long> ids = lines.stream().filter(l -> l.startsWith("id:")).map(l -> Long.parseLong(l.substring(3).trim())).toList();
        assertThat(ids).isSorted().doesNotHaveDuplicates();

        Round round = api().get(council.officerToken(), "/api/v1/councils/" + c + "/agents/" + agent + "/round?marketId=" + market,
                Round.class).body();
        assertThat(round.done()).containsExactlyInAnyOrder(stalls.get(0), stalls.get(1), stalls.get(2));
        assertThat(round.notReached()).containsExactly(stalls.get(3));
        stream.cancel(true);
    }

    @Test
    @DisplayName("another council cannot open the feed of this council's market")
    void otherCouncil() {
        Fixture council = newCouncil();
        Fixture other = newCouncil();
        UUID market = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                        + "values (:id, :c, 'LVX', 'Live', '20:00', now())").param("id", market).param("c", council.councilId()).update());

        assertThat(api().get(other.adminToken(), "/api/v1/councils/" + council.councilId() + "/markets/" + market + "/map-events",
                String.class).status()).isEqualTo(403);
        assertThat(api().get(other.adminToken(), "/api/v1/councils/" + other.councilId() + "/markets/" + market + "/map-events",
                String.class).status()).isEqualTo(404);
    }
}
