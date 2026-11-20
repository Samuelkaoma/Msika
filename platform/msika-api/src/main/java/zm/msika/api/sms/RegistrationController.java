package zm.msika.api.sms;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.sms.SmsTemplate;
import zm.msika.core.stall.AllocateStall;
import zm.msika.core.trader.RegisterTrader;

/**
 * Registration requests raised by {@code REG} (card M-19): the market office lists them and approves or rejects
 * each one. Approving registers the trader (if the number is new), allocates the stall, and texts the trader to
 * reply {@code YES}. Officers can read; changing needs an admin (the usual council rule).
 *
 * <p>Card M-64 moves these into the approvals inbox, where the person who decides is never the one who raised it.
 */
@RestController
class RegistrationController {

    record RegistrationBody(UUID id, UUID stallId, String stallCode, String phone, String status, UUID traderId,
            Instant createdAt, Instant decidedAt, String reason) {}

    record ApproveRequest(String displayName, String language) {}

    record RejectRequest(String reason) {}

    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final RegisterTrader registerTrader;
    private final AllocateStall allocateStall;
    private final SmsQueries queries;
    private final SmsStore sms;
    private final AuditLog audit;
    private final Clock clock;

    RegistrationController(TenantContext tenant, JdbcClient jdbc, RegisterTrader registerTrader,
            AllocateStall allocateStall, SmsQueries queries, SmsStore sms, AuditLog audit, Clock clock) {
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.registerTrader = registerTrader;
        this.allocateStall = allocateStall;
        this.queries = queries;
        this.sms = sms;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/api/v1/councils/{councilId}/registrations")
    List<RegistrationBody> list(@PathVariable UUID councilId) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("select r.id, r.stall_id, s.code, r.msisdn, r.status, "
                        + "r.trader_id, r.created_at, r.decided_at, r.reason from registration_request r "
                        + "join stall s on s.id = r.stall_id where r.council_id = :c order by r.created_at desc limit 500")
                .param("c", councilId)
                .query((rs, n) -> new RegistrationBody(rs.getObject("id", UUID.class), rs.getObject("stall_id", UUID.class),
                        rs.getString("code"), rs.getString("msisdn"), rs.getString("status"),
                        rs.getObject("trader_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("decided_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("decided_at", OffsetDateTime.class).toInstant(),
                        rs.getString("reason")))
                .list());
    }

    private record Pending(UUID stallId, String msisdn) {}

    private Pending lockPending(UUID councilId, UUID id) {
        record Row(UUID stallId, String msisdn, String status) {}
        Row row = jdbc.sql("select stall_id, msisdn, status from registration_request where council_id = :c and id = :id "
                        + "for update")
                .param("c", councilId).param("id", id)
                .query((rs, n) -> new Row(rs.getObject("stall_id", UUID.class), rs.getString("msisdn"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new NotFoundException("no registration request " + id));
        if (!"PENDING".equals(row.status())) {
            throw new ConflictException("this registration request has already been decided");
        }
        return new Pending(row.stallId(), row.msisdn());
    }

    @PostMapping("/api/v1/councils/{councilId}/registrations/{id}/approve")
    RegistrationBody approve(@PathVariable UUID councilId, @PathVariable UUID id, @RequestBody ApproveRequest request) {
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new InvalidInputException("displayName is required: the trader's name as the market office knows it");
        }
        tenant.inCouncil(councilId, () -> {
            Pending p = lockPending(councilId, id);
            UUID traderId = queries.traderByPhone(councilId, p.msisdn()).orElseGet(() -> registerTrader.execute(councilId,
                    p.msisdn(), request.displayName(), request.language() == null ? "en" : request.language()).id());
            allocateStall.execute(councilId, p.stallId(), traderId);
            Instant now = clock.instant();
            jdbc.sql("update registration_request set status = 'APPROVED', trader_id = :t, decided_at = :at, "
                            + "decided_by = :by where council_id = :c and id = :id")
                    .param("t", traderId).param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    .param("by", CurrentUser.actor()).param("c", councilId).param("id", id).update();
            String code = queries.stallCode(councilId, p.stallId()).orElseThrow();
            sms.enqueue(councilId, p.msisdn(), SmsTemplate.REG_APPROVED.render("STALL", code), SmsTemplate.REG_APPROVED.name(),
                    null, now);
            audit.record(councilId, CurrentUser.actor(), "registration.approve", id + " stall " + code);
        });
        return find(councilId, id);
    }

    @PostMapping("/api/v1/councils/{councilId}/registrations/{id}/reject")
    RegistrationBody reject(@PathVariable UUID councilId, @PathVariable UUID id, @RequestBody RejectRequest request) {
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty() || reason.length() > 200) {
            throw new InvalidInputException("a reason of 1 to 200 characters is required");
        }
        tenant.inCouncil(councilId, () -> {
            Pending p = lockPending(councilId, id);
            Instant now = clock.instant();
            jdbc.sql("update registration_request set status = 'REJECTED', reason = :r, decided_at = :at, decided_by = :by "
                            + "where council_id = :c and id = :id")
                    .param("r", reason).param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    .param("by", CurrentUser.actor()).param("c", councilId).param("id", id).update();
            String code = queries.stallCode(councilId, p.stallId()).orElseThrow();
            sms.enqueue(councilId, p.msisdn(), SmsTemplate.REG_REJECTED.render("STALL", code), SmsTemplate.REG_REJECTED.name(),
                    null, now);
            audit.record(councilId, CurrentUser.actor(), "registration.reject", id.toString());
        });
        return find(councilId, id);
    }

    private RegistrationBody find(UUID councilId, UUID id) {
        return list(councilId).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("no registration request " + id));
    }
}
