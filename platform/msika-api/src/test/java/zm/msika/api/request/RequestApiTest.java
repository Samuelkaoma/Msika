package zm.msika.api.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import zm.msika.api.ApiClient;
import zm.msika.api.ApiTest;

/** The request engine, proved with stall changes and agent suspension (card M-62). */
class RequestApiTest extends ApiTest {

    record NewUser(String email, String password, String role, Set<UUID> marketIds) {}

    record Id(UUID id) {}

    record NewMarket(String code, String name, String location) {}

    record Stall(UUID id, String code, String stallClass, String status) {}

    record NewPerson(String phone, String displayName, String language) {}

    record Allocate(UUID traderId) {}

    record Evidence(String type, String id, String note) {}

    record Raise(String kind, UUID subjectId, Map<String, String> effect, String reason, List<Evidence> evidence) {}

    record Decide(String decision, String reason, UUID correctsDecisionId) {}

    record Bulk(List<UUID> requestIds, String reason) {}

    record HandOver(UUID toUserId) {}

    record Moved(int moved) {}

    record Item(UUID id, String kind, String effect, String status, String problem, boolean overdue, boolean escalated,
            int approvals, String refusal, boolean handedToMe) {}

    record Decision(UUID id, UUID decidedBy, String decision, String reason) {}

    record Detail(Item request, List<Evidence> evidence, List<Decision> decisions) {}

    record BulkResult(UUID requestId, String status, String problem) {}

    record Policy(List<Set<String>> steps, int deadlineDays, boolean bulkAllowed, int sampleRate) {}

    record ErrorBody(String error, String message) {}

    record Person(UUID id, String token) {}

    @Autowired
    RequestService requests;

    private Fixture council;
    private UUID market;
    private List<Stall> stalls;
    private Person master;
    private Person master2;
    private Person admin2;

    private String base() {
        return "/api/v1/councils/" + council.councilId();
    }

    private Person person(String role, Set<UUID> markets) {
        String email = "u-" + UUID.randomUUID() + "@example.test";
        var r = api().post(council.adminToken(), base() + "/users", new NewUser(email, USER_PASSWORD, role, markets), Id.class);
        assertThat(r.status()).isEqualTo(201);
        return new Person(r.body().id(), login(email, USER_PASSWORD));
    }

    private UUID newMarket(String code, int count) {
        UUID id = api().post(council.adminToken(), base() + "/markets", new NewMarket(code, code, "Lusaka"), Id.class).body().id();
        StringBuilder csv = new StringBuilder("section,number,class\n");
        for (int i = 1; i <= count; i++) {
            csv.append("A,").append(i).append(",STANDARD\n");
        }
        assertThat(api().postCsv(council.adminToken(), base() + "/markets/" + id + "/stalls/import", csv.toString(), String.class)
                .status()).isEqualTo(201);
        return id;
    }

    private List<Stall> stallsOf(UUID marketId) {
        return List.of(api().get(council.adminToken(), base() + "/markets/" + marketId + "/stalls", Stall[].class).body());
    }

    private Stall stall(int i) {
        return stallsOf(market).stream().filter(s -> s.id().equals(stalls.get(i).id())).findFirst().orElseThrow();
    }

    @BeforeEach
    void setUp() {
        council = newCouncil();
        market = newMarket("RQA", 12);
        stalls = stallsOf(market);
        master = person("MARKET_MASTER", Set.of(market));
        master2 = person("MARKET_MASTER", Set.of(market));
        admin2 = person("COUNCIL_ADMIN", Set.of());
    }

    private <T> ApiClient.Result<T> raise(String token, Raise body, String key, Class<T> type) {
        return api().send(token, HttpMethod.POST, base() + "/requests", body, MediaType.APPLICATION_JSON, type, key);
    }

    private UUID raised(Person by, String kind, UUID subject, Map<String, String> effect) {
        var r = raise(by.token(), new Raise(kind, subject, effect, "seen on the round", List.of()), UUID.randomUUID().toString(), Detail.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body().request().id();
    }

    private ApiClient.Result<Detail> decide(String token, UUID request, String decision, String reason) {
        return api().post(token, base() + "/requests/" + request + "/decisions", new Decide(decision, reason, null), Detail.class);
    }

    private Detail open(String token, UUID request) {
        return api().get(token, base() + "/requests/" + request, Detail.class).body();
    }

    private List<UUID> queue(String token, String name) {
        return List.of(api().get(token, base() + "/inbox?queue=" + name, Item[].class).body()).stream().map(Item::id).toList();
    }

    private List<String> texts(String template) {
        return ownerJdbc().sql("select body from sms_outbound where council_id = :c and template = :t").param("c", council.councilId())
                .param("t", template).query(String.class).list();
    }

    @Test
    @DisplayName("a stall change: raised with evidence, shown in words, decided by somebody else, carried out, the trader told")
    void stallChange() {
        UUID trader = api().post(council.adminToken(), base() + "/traders", new NewPerson("0977120011", "Made Up", "en"), Id.class)
                .body().id();
        api().put(council.adminToken(), base() + "/stalls/" + stalls.get(0).id() + "/allocation", new Allocate(trader), String.class);
        Raise body = new Raise("STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"), "measured",
                List.of(new Evidence("SURVEY", "S-1", "twice the size")));
        var r = raise(master.token(), body, "key-1", Detail.class);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().request().effect()).contains("RQA-A-").contains("from class STANDARD to LARGE");
        assertThat(r.body().evidence()).containsExactly(new Evidence("SURVEY", "S-1", "twice the size"));
        UUID id = r.body().request().id();
        var again = raise(master.token(), body, "key-1", Detail.class);
        assertThat(again.status()).as("the same command again").isEqualTo(200);
        assertThat(again.body().request().id()).isEqualTo(id);
        assertThat(raise(master.token(), new Raise("STALL_CLASS", stalls.get(1).id(), Map.of("stallClass", "LARGE"), "x", List.of()),
                "key-1", String.class).status()).as("the key for something else").isEqualTo(409);
        assertThat(raise(master2.token(), body, "key-2", String.class).status()).as("already asked for").isEqualTo(409);

        assertThat(queue(master.token(), "raised")).containsExactly(id);
        assertThat(queue(master.token(), "mine")).isEmpty();
        assertThat(queue(master2.token(), "mine")).containsExactly(id);
        assertThat(stall(0).stallClass()).isEqualTo("STANDARD");
        assertThat(decide(master2.token(), id, "APPROVE", null).body().request().status()).isEqualTo("APPROVED");
        assertThat(decide(master2.token(), id, "APPROVE", null).status()).as("sent again").isEqualTo(200);
        assertThat(stall(0).stallClass()).isEqualTo("LARGE");
        assertThat(texts("STALL_CLASS_CHANGED")).as("told once").hasSize(1);
        assertThat(decide(admin2.token(), id, "REJECT", "no").status()).as("closed").isEqualTo(409);
    }

    @Test
    @DisplayName("suspending takes a master then an admin; reinstating puts the stall back as it was")
    void twoApprovals() {
        UUID trader = api().post(council.adminToken(), base() + "/traders", new NewPerson("0977120012", "Made Up", "en"), Id.class)
                .body().id();
        api().put(council.adminToken(), base() + "/stalls/" + stalls.get(0).id() + "/allocation", new Allocate(trader), String.class);
        UUID id = raised(master, "STALL_SUSPEND", stalls.get(0).id(), Map.of());

        assertThat(decide(master2.token(), id, "APPROVE", null).body().request().status()).isEqualTo("OPEN");
        assertThat(stall(0).status()).isEqualTo("ALLOCATED");
        assertThat(decide(council.adminToken(), id, "APPROVE", null).status()).as("a second approval needs a reason").isEqualTo(400);
        assertThat(decide(council.adminToken(), id, "APPROVE", "agreed").body().request().status()).isEqualTo("APPROVED");
        assertThat(stall(0).status()).isEqualTo("SUSPENDED");

        UUID back = raised(master, "STALL_REINSTATE", stalls.get(0).id(), Map.of());
        decide(master2.token(), back, "APPROVE", null);
        assertThat(stall(0).status()).isEqualTo("ALLOCATED");
        assertThat(texts("STALL_SUSPENDED")).hasSize(1);
        assertThat(texts("STALL_REINSTATED")).hasSize(1);
    }

    @Test
    @DisplayName("the raiser cannot decide, in the API or the database")
    void raiserCannotDecide() {
        UUID id = raised(master, "STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"));

        assertThat(decide(master.token(), id, "APPROVE", "mine").status()).isEqualTo(409);
        assertThat(decide(master.token(), id, "REJECT", "mine").status()).isEqualTo(409);
        assertThatThrownBy(() -> insertDecision(id, master.id(), "APPROVE", null)).hasMessageContaining("raised a request cannot decide");
        assertThatThrownBy(() -> ownerJdbc().sql("update request set raised_by_id = 'x' where id = :r").param("r", id).update())
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> ownerJdbc().sql("update request set status = 'APPROVED' where id = :r").param("r", id).update())
                .hasMessageContaining("all its approvals");
    }

    private void insertDecision(UUID request, UUID by, String decision, String reason) {
        ownerJdbc().sql("insert into request_decision (id, council_id, request_id, decided_by, decision, reason, decided_at) "
                        + "values (gen_random_uuid(), :c, :r, :u, :d, :reason, now())").param("c", council.councilId())
                .param("r", request).param("u", by).param("d", decision).param("reason", reason, java.sql.Types.VARCHAR).update();
    }

    @Test
    @DisplayName("one person cannot give both of two approvals, in the API or the database")
    void onePersonOneApproval() {
        UUID id = raised(master, "STALL_SUSPEND", stalls.get(0).id(), Map.of());
        assertThat(decide(council.adminToken(), id, "APPROVE", null).body().request().approvals()).isEqualTo(1);

        Detail again = decide(council.adminToken(), id, "APPROVE", "and again").body();
        assertThat(again.request().approvals()).as("the same decision again changes nothing").isEqualTo(1);
        assertThat(again.request().status()).isEqualTo("OPEN");
        assertThat(again.request().refusal()).contains("two approvals");
        assertThatThrownBy(() -> insertDecision(id, council.adminId(), "APPROVE", "again")).hasMessageContaining("one_approval");
        assertThat(stall(0).status()).isEqualTo("VACANT");
        assertThat(decide(admin2.token(), id, "APPROVE", "a second person").body().request().status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an effect that fails leaves the request open with its reason, and no decision recorded")
    void effectFails() {
        UUID id = raised(master, "STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"));
        ownerJdbc().sql("update stall set stall_class = 'LARGE' where id = :s").param("s", stalls.get(0).id()).update();

        var r = api().post(master2.token(), base() + "/requests/" + id + "/decisions", new Decide("APPROVE", null, null), ErrorBody.class);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().message()).contains("already has class LARGE").contains("stays open");
        Detail d = open(master2.token(), id);
        assertThat(d.request().status()).isEqualTo("OPEN");
        assertThat(d.request().problem()).contains("already has class LARGE");
        assertThat(d.decisions()).isEmpty();
        assertThat(texts("STALL_CLASS_CHANGED")).isEmpty();
        assertThat(decide(master2.token(), id, "REJECT", "already done").body().request().status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a decision row cannot be updated or deleted; a closed request stays closed; a rejection says why")
    void appendOnly() {
        UUID id = raised(master, "STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"));
        assertThat(decide(master2.token(), id, "REJECT", " ").status()).isEqualTo(400);
        decide(master2.token(), id, "REJECT", "not needed");

        assertThatThrownBy(() -> ownerJdbc().sql("update request_decision set reason = 'x' where request_id = :r").param("r", id).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> ownerJdbc().sql("delete from request_decision where request_id = :r").param("r", id).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> ownerJdbc().sql("update request set status = 'OPEN' where id = :r").param("r", id).update())
                .hasMessageContaining("stays closed");
        assertThatThrownBy(() -> insertDecision(id, council.adminId(), "APPROVE", "late")).hasMessageContaining("not open");
    }

    @Test
    @DisplayName("an approval given by mistake is corrected by a new decision that points at it")
    void correction() {
        UUID id = raised(master, "STALL_SUSPEND", stalls.get(0).id(), Map.of());
        Decision first = decide(master2.token(), id, "APPROVE", null).body().decisions().getFirst();

        assertThat(api().post(master2.token(), base() + "/requests/" + id + "/decisions",
                new Decide("REJECT", "wrong stall", first.id()), Detail.class).body().request().status()).isEqualTo("REJECTED");
        assertThat(open(master2.token(), id).decisions()).hasSize(2);
        UUID other = raised(master, "STALL_SUSPEND", stalls.get(1).id(), Map.of());
        decide(master2.token(), other, "APPROVE", null);
        assertThat(api().post(admin2.token(), base() + "/requests/" + other + "/decisions", new Decide("REJECT", "not mine", first.id()),
                String.class).status()).as("somebody else's decision").isEqualTo(400);
    }

    @Test
    @DisplayName("an overdue request escalates: an admin may then decide it, and it shows as overdue")
    void escalates() {
        Policy masterOnly = new Policy(List.of(Set.of("MARKET_MASTER")), 1, true, 0);
        assertThat(api().put(council.adminToken(), base() + "/policies/STALL_CLASS", masterOnly, String.class).status()).isEqualTo(200);
        UUID id = raised(master, "STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"));
        assertThat(decide(council.adminToken(), id, "APPROVE", null).status()).isEqualTo(403);
        assertThat(requests.escalate(council.councilId())).as("not due yet").isZero();
        // The only way to make it late without waiting: what was asked for cannot be changed while its trigger is on.
        ownerJdbc().sql("alter table request disable trigger request_rules").update();
        try {
            ownerJdbc().sql("update request set due_at = now() - interval '1 hour' where id = :r").param("r", id).update();
        } finally {
            ownerJdbc().sql("alter table request enable trigger request_rules").update();
        }

        assertThat(requests.escalate(council.councilId())).isEqualTo(1);
        assertThat(requests.escalate(council.councilId())).as("once").isZero();
        assertThat(queue(council.adminToken(), "overdue")).containsExactly(id);
        Item seen = open(master2.token(), id).request();
        assertThat(seen.escalated()).as("the original decider sees it was escalated").isTrue();
        assertThat(seen.refusal()).as("and may still decide").isNull();
        assertThat(queue(council.adminToken(), "mine")).contains(id);
        assertThat(decide(council.adminToken(), id, "APPROVE", null).body().request().status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("a council can tighten a policy and cannot loosen it below its minimum")
    void policies() {
        String path = base() + "/policies/STALL_SUSPEND";
        Set<String> admin = Set.of("COUNCIL_ADMIN");
        assertThat(api().put(council.adminToken(), path, new Policy(List.of(admin), 5, false, 0), String.class).status()).isEqualTo(400);
        assertThat(api().put(council.adminToken(), path, new Policy(List.of(admin, Set.of("MARKET_MASTER")), 5, false, 0), String.class)
                .status()).isEqualTo(400);
        assertThat(api().put(council.adminToken(), path, new Policy(List.of(admin, admin), 9, false, 0), String.class).status()).isEqualTo(400);
        assertThat(api().put(council.adminToken(), path, new Policy(List.of(admin, admin), 5, true, 0), String.class).status()).isEqualTo(400);
        assertThat(api().put(master.token(), path, new Policy(List.of(admin, admin), 2, false, 0), String.class).status()).isEqualTo(403);
        UUID before = raised(master, "STALL_SUSPEND", stalls.get(0).id(), Map.of());
        assertThat(api().put(council.adminToken(), path, new Policy(List.of(admin, admin), 2, false, 0), String.class).status()).isEqualTo(200);

        assertThat(decide(master2.token(), before, "APPROVE", null).status()).as("raised under the old policy").isEqualTo(200);
        UUID after = raised(master, "STALL_SUSPEND", stalls.get(1).id(), Map.of());
        assertThat(decide(master2.token(), after, "APPROVE", null).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("bulk approval: only where allowed, after opening the sample, one decision each, one failure does not stop the rest")
    void bulk() {
        api().put(council.adminToken(), base() + "/policies/STALL_CLASS",
                new Policy(List.of(Set.of("MARKET_MASTER")), 5, true, 10), String.class);
        List<UUID> ids = new ArrayList<>();
        for (Stall s : stalls) {
            ids.add(raised(master, "STALL_CLASS", s.id(), Map.of("stallClass", "LARGE")));
        }
        ownerJdbc().sql("update stall set stall_class = 'LARGE' where id = :s").param("s", stalls.get(3).id()).update();
        String path = base() + "/requests/bulk-decisions";
        open(master2.token(), ids.get(0));
        assertThat(api().post(master2.token(), path, new Bulk(ids, null), String.class).status()).as("2 of 12 must be opened").isEqualTo(409);
        open(master2.token(), ids.get(0));
        assertThat(api().post(master2.token(), path, new Bulk(ids, null), String.class).status()).as("the same one twice").isEqualTo(409);
        open(master2.token(), ids.get(1));

        BulkResult[] results = api().post(master2.token(), path, new Bulk(ids, null), BulkResult[].class).body();

        assertThat(results).hasSize(12);
        assertThat(results[3].status()).isEqualTo("OPEN");
        assertThat(results[3].problem()).contains("already has class");
        assertThat(results).filteredOn(r -> r.status().equals("APPROVED")).hasSize(11);
        assertThat(ownerJdbc().sql("select count(*) from request_decision where council_id = :c").param("c", council.councilId())
                .query(Integer.class).single()).isEqualTo(11);
        UUID suspend = raised(master, "STALL_SUSPEND", stalls.get(0).id(), Map.of());
        assertThat(api().post(master2.token(), path, new Bulk(List.of(suspend), null), String.class).status()).isEqualTo(409);
        assertThat(api().post(master2.token(), path, new Bulk(List.of(suspend, ids.get(3)), null), String.class).status())
                .as("two kinds").isEqualTo(400);
    }

    @Test
    @DisplayName("going on leave: open items go to a colleague who may decide them, and to nobody else")
    void handOver() {
        UUID id = raised(master, "STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"));
        String path = base() + "/requests/hand-over";

        assertThat(api().post(master2.token(), path, new HandOver(council.officerId()), Moved.class).body().moved())
                .as("an officer may not decide it").isZero();
        assertThat(api().post(master2.token(), path, new HandOver(master.id()), Moved.class).body().moved())
                .as("the raiser may not decide it").isZero();
        assertThat(queue(master2.token(), "mine")).containsExactly(id);
        assertThat(api().post(master2.token(), path, new HandOver(admin2.id()), Moved.class).body().moved()).isEqualTo(1);
        assertThat(queue(master2.token(), "mine")).isEmpty();
        assertThat(open(admin2.token(), id).request().handedToMe()).isTrue();
        assertThat(decide(master2.token(), id, "APPROVE", null).status()).isEqualTo(409);
        assertThat(api().post(admin2.token(), path, new HandOver(master2.id()), Moved.class).body().moved()).as("and back").isEqualTo(1);
        assertThat(queue(master2.token(), "mine")).containsExactly(id);
        assertThat(api().post(master2.token(), path, new HandOver(UUID.randomUUID()), String.class).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("agent suspension: raised by a market master, decided by an admin, and the agent can be let back")
    void agentSuspension() {
        UUID agent = api().post(council.adminToken(), base() + "/agents", new NewPerson("0977120021", "Agent Made Up", null), Id.class)
                .body().id();
        UUID id = raised(master, "AGENT_SUSPENSION", agent, Map.of("active", "false"));
        assertThat(decide(master2.token(), id, "APPROVE", null).status()).isEqualTo(403);
        assertThat(decide(council.adminToken(), id, "APPROVE", null).body().request().status()).isEqualTo("APPROVED");
        assertThat(ownerJdbc().sql("select active from agent where id = :a").param("a", agent).query(Boolean.class).single()).isFalse();
        assertThat(texts("AGENT_SUSPENDED")).hasSize(1);
        UUID back = raised(master, "AGENT_SUSPENSION", agent, Map.of("active", "true"));
        decide(council.adminToken(), back, "APPROVE", null);
        assertThat(ownerJdbc().sql("select active from agent where id = :a").param("a", agent).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("who may raise: a master in their own market; not an admin, an officer, an operator, or a master elsewhere")
    void whoMayRaise() {
        UUID other = newMarket("RQB", 1);
        Raise elsewhere = new Raise("STALL_CLASS", stallsOf(other).getFirst().id(), Map.of("stallClass", "LARGE"), "x", List.of());
        Raise here = new Raise("STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"), "x", List.of());

        assertThat(raise(master.token(), elsewhere, "k", String.class).status()).isEqualTo(403);
        assertThat(raise(council.adminToken(), here, "k", String.class).status()).isEqualTo(403);
        assertThat(raise(council.officerToken(), here, "k", String.class).status()).isEqualTo(403);
        assertThat(raise(operatorToken(), here, "k", String.class).status()).isEqualTo(403);
        assertThat(raise(master.token(), here, null, String.class).status()).as("no Idempotency-Key").isEqualTo(400);
        assertThat(raise(master.token(), new Raise("STALL_CLASS", stalls.get(0).id(), Map.of(), "x", List.of()), "k", String.class)
                .status()).as("no class").isEqualTo(400);
        assertThat(raise(master.token(), new Raise("NOPE", stalls.get(0).id(), Map.of(), "x", List.of()), "k", String.class).status())
                .isEqualTo(400);
        UUID id = raised(master, "STALL_CLASS", stalls.get(0).id(), Map.of("stallClass", "LARGE"));
        Fixture stranger = newCouncil();
        assertThat(api().get(stranger.adminToken(), "/api/v1/councils/" + stranger.councilId() + "/requests/" + id, String.class)
                .status()).as("another council").isEqualTo(404);
        assertThat(api().post(master2.token(), base() + "/requests/" + id + "/withdraw", null, String.class).status()).isEqualTo(409);
        assertThat(api().post(master.token(), base() + "/requests/" + id + "/withdraw", null, Detail.class).body().request().status())
                .isEqualTo("WITHDRAWN");
    }
}
