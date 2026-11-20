package zm.msika.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.api.tenancy.TenantContext;

/** Every change a council user makes writes an audit row, and audit rows can never be changed. */
class AuditLogTest extends ApiTest {

    record AuditRow(String actor, String action, String target) {}

    record MarketRequest(String code, String name, String location) {}

    record MarketBody(UUID id, String code) {}

    record StallBody(UUID id, String code) {}

    record AllocateRequest(UUID traderId) {}

    record ActiveRequest(Boolean active) {}

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    private List<AuditRow> auditOf(UUID council) {
        return tenant.inCouncil(council, () -> jdbc
                .sql("select actor, action, target from audit_log where council_id = :c order by at, id")
                .param("c", council)
                .query((rs, row) -> new AuditRow(rs.getString("actor"), rs.getString("action"), rs.getString("target")))
                .list());
    }

    @Test
    @DisplayName("creating a council, a user, a market, an import, an allocation and a free are each recorded")
    void everyChangeIsRecorded() {
        Fixture council = newCouncil();
        String base = "/api/v1/councils/" + council.councilId();
        String admin = council.adminToken();

        Result<MarketBody> market = api().post(admin, base + "/markets", new MarketRequest("SOW", "Soweto", ""), MarketBody.class);
        api().postCsv(admin, base + "/markets/" + market.body().id() + "/stalls/import",
                "section,number,class\nB,1,STANDARD\nB,2,STANDARD\n", Void.class);
        StallBody stall = api().get(admin, base + "/markets/" + market.body().id() + "/stalls", StallBody[].class).body()[0];
        UUID trader = UUID.randomUUID();
        tenant.inCouncil(council.councilId(), () -> jdbc
                .sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, '+260977000001', 'T')")
                .param("id", trader).param("c", council.councilId()).update());
        api().put(admin, base + "/stalls/" + stall.id() + "/allocation", new AllocateRequest(trader), Void.class);
        api().delete(admin, base + "/stalls/" + stall.id() + "/allocation", Void.class);
        api().put(admin, base + "/users/" + council.officerId() + "/active", new ActiveRequest(false), Void.class);

        List<AuditRow> audit = auditOf(council.councilId());

        assertThat(audit).extracting(AuditRow::action).containsExactly(
                "council.create", "user.create", "user.create", "market.create", "stalls.import",
                "stall.allocate", "stall.free", "user.deactivate");
        assertThat(audit.get(0).actor()).as("the operator created the council").isNotEqualTo(council.adminId().toString());
        assertThat(audit.subList(3, 8)).extracting(AuditRow::actor).as("the admin did the rest")
                .containsOnly(council.adminId().toString());
        assertThat(audit.get(3).target()).isEqualTo("SOW");
        assertThat(audit.get(4).target()).contains("2 stalls");
        assertThat(audit.get(7).target()).isEqualTo(council.officerId().toString());
    }

    @Test
    @DisplayName("reading data writes no audit rows")
    void readsAreNotAudited() {
        Fixture council = newCouncil();
        int before = auditOf(council.councilId()).size();

        api().get(council.adminToken(), "/api/v1/councils/" + council.councilId() + "/markets", Void.class);
        api().get(council.officerToken(), "/api/v1/councils/" + council.councilId() + "/markets", Void.class);
        api().get(council.adminToken(), "/api/v1/auth/me", Void.class);

        assertThat(auditOf(council.councilId())).hasSize(before);
    }

    @Test
    @DisplayName("a refused change (officer, 403) writes nothing, and a failed one (409) leaves no audit row")
    void refusedAndFailedChangesAreNotRecorded() {
        Fixture council = newCouncil();
        String base = "/api/v1/councils/" + council.councilId();
        api().post(council.adminToken(), base + "/markets", new MarketRequest("SOW", "Soweto", ""), Void.class);
        int before = auditOf(council.councilId()).size();

        api().post(council.officerToken(), base + "/markets", new MarketRequest("OFF", "Officer", ""), Void.class);
        api().post(council.adminToken(), base + "/markets", new MarketRequest("SOW", "Duplicate", ""), Void.class);

        assertThat(auditOf(council.councilId())).hasSize(before);
    }

    @Test
    @DisplayName("the database refuses to update or delete an audit row, even for the app role")
    void auditLogIsAppendOnly() {
        Fixture council = newCouncil();
        assertThat(auditOf(council.councilId())).isNotEmpty();

        assertThatThrownBy(() -> tenant.inCouncil(council.councilId(),
                () -> jdbc.sql("update audit_log set action = 'forged'").update()))
                .rootCause().hasMessageContaining("permission denied for table audit_log");
        assertThatThrownBy(() -> tenant.inCouncil(council.councilId(),
                () -> jdbc.sql("delete from audit_log").update()))
                .rootCause().hasMessageContaining("permission denied for table audit_log");
        assertThatThrownBy(() -> tenant.inCouncil(council.councilId(),
                () -> jdbc.sql("truncate audit_log").update()))
                .rootCause().hasMessageContaining("permission denied for table audit_log");
    }

    @Test
    @DisplayName("a council's audit log is invisible to every other council")
    void auditLogIsPerCouncil() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();

        tenant.inCouncil(a.councilId(), () -> {
            List<UUID> councils = jdbc.sql("select council_id from audit_log").query(UUID.class).list();
            assertThat(councils).isNotEmpty().containsOnly(a.councilId());
        });
        assertThat(auditOf(b.councilId())).isNotEmpty();
    }
}
