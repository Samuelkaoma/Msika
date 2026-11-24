package zm.msika.api.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.tenancy.TenantContext;

/** The council's audit log, newest first, for auditors and admins (card M-61). */
@RestController
class AuditLogController {

    record AuditRow(String actor, String action, String target, Instant at) {}

    private final TenantContext tenant;
    private final JdbcClient jdbc;

    AuditLogController(TenantContext tenant, JdbcClient jdbc) {
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/councils/{councilId}/audit-log")
    List<AuditRow> auditLog(@PathVariable UUID councilId) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("select actor, action, target, at from audit_log "
                        + "where council_id = :c order by at desc, id limit 500")
                .param("c", councilId)
                .query((rs, n) -> new AuditRow(rs.getString("actor"), rs.getString("action"), rs.getString("target"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()))
                .list());
    }
}
