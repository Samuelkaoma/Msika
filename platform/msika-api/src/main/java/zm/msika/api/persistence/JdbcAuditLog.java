package zm.msika.api.persistence;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.audit.AuditLog;

/**
 * Writes the audit log. The caller must already have set the council on the transaction
 * ({@code TenantContext.use}), because the table is protected by row-level security. The database
 * refuses to update or delete audit rows.
 */
@Repository
class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(UUID councilId, String actor, String action, String target) {
        jdbc.sql("insert into audit_log (id, council_id, actor, action, target, at) "
                        + "values (:id, :councilId, :actor, :action, :target, :at)")
                .param("id", UUID.randomUUID())
                .param("councilId", councilId)
                .param("actor", actor)
                .param("action", action)
                .param("target", target)
                .param("at", OffsetDateTime.now(clock))
                .update();
    }
}
