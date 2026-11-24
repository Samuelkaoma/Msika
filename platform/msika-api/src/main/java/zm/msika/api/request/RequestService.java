package zm.msika.api.request;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.security.MsikaPrincipal;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.request.DecisionRules;
import zm.msika.core.request.RequestKind;
import zm.msika.core.request.RequestPolicy;
import zm.msika.core.request.WorkingDays;
import zm.msika.core.user.CouncilUser;
import zm.msika.core.user.Role;
import zm.msika.core.user.UserRepository;

/** The request engine (card M-62): every council decision is raised, decided and carried out here. */
@Service
public class RequestService {

    private static final int MAX_BULK = 500;

    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RequestEffects effects;
    private final AuditLog audit;
    private final CouncilRepository councils;
    private final UserRepository users;
    private final Clock clock;

    RequestService(TenantContext tenant, JdbcClient jdbc, JsonMapper json, RequestEffects effects, AuditLog audit,
            CouncilRepository councils, UserRepository users, Clock clock) {
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.json = json;
        this.effects = effects;
        this.audit = audit;
        this.councils = councils;
        this.users = users;
        this.clock = clock;
    }

    public record Evidence(String type, String id, String note) {}

    public record Decision(UUID id, UUID decidedBy, String decidedByEmail, String decision, String reason, Instant at,
            UUID corrects) {}

    /** @param refusal why the person asking cannot decide it now, or null if they can */
    public record Item(UUID id, RequestKind kind, String subjectType, UUID subjectId, UUID marketId, String raisedByType,
            String raisedById, String raisedByEmail, String reason, String effect, String status, Instant dueAt,
            boolean overdue, boolean escalated, String problem, Instant createdAt, int approvals, RequestPolicy policy,
            String refusal, boolean handedToMe) {}

    public record Detail(Item request, List<Evidence> evidence, List<Decision> decisions) {}

    public record Raised(Detail detail, boolean created) {}

    public record BulkResult(UUID requestId, String status, String problem) {}

    /** What went wrong while carrying out an effect. The decision is rolled back with it. */
    private static final class EffectFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EffectFailed(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Policies
    // ---------------------------------------------------------------------------------------------------------

    public Map<RequestKind, RequestPolicy> policies(UUID council) {
        return tenant.inCouncil(council, () -> {
            Map<RequestKind, RequestPolicy> all = new java.util.EnumMap<>(RequestKind.class);
            for (RequestKind k : RequestKind.values()) {
                all.put(k, policy(council, k));
            }
            return all;
        });
    }

    private RequestPolicy policy(UUID council, RequestKind kind) {
        return jdbc.sql("select decider_roles::text as roles, deadline_days, bulk_allowed, sample_rate from request_policy "
                        + "where council_id = :c and kind = :k").param("c", council).param("k", kind.name())
                .query((rs, n) -> new RequestPolicy(json.readValue(rs.getString("roles"), new TypeReference<List<Set<Role>>>() {}),
                        rs.getInt("deadline_days"), rs.getBoolean("bulk_allowed"), rs.getInt("sample_rate")))
                .optional().orElse(kind.minimum());
    }

    /** Sets the council's policy for one kind. It applies to requests raised from now on. */
    public RequestPolicy setPolicy(UUID council, RequestKind kind, RequestPolicy wanted) {
        wanted.requireAtLeast(kind.minimum());
        return tenant.inCouncil(council, () -> {
            jdbc.sql("insert into request_policy (council_id, kind, decider_roles, approvals_required, deadline_days, bulk_allowed, "
                            + "sample_rate) values (:c, :k, :r::jsonb, :n, :d, :b, :s) on conflict (council_id, kind) do update set "
                            + "decider_roles = excluded.decider_roles, approvals_required = excluded.approvals_required, "
                            + "deadline_days = excluded.deadline_days, bulk_allowed = excluded.bulk_allowed, "
                            + "sample_rate = excluded.sample_rate")
                    .param("c", council).param("k", kind.name()).param("r", json.writeValueAsString(wanted.steps()))
                    .param("n", wanted.steps().size()).param("d", wanted.deadlineDays()).param("b", wanted.bulkAllowed())
                    .param("s", wanted.sampleRate()).update();
            audit.record(council, CurrentUser.actor(), "policy.change", kind.name());
            return wanted;
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Raising
    // ---------------------------------------------------------------------------------------------------------

    public Raised raise(UUID council, RequestKind kind, UUID subject, Map<String, String> effect, String reason,
            List<Evidence> evidence, String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new InvalidInputException("an Idempotency-Key header of 1 to 100 characters is required");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new InvalidInputException("reason must be 1 to 500 characters");
        }
        for (Evidence e : evidence) {
            if (e.type() == null || e.type().isBlank() || e.type().length() > 40 || e.id() == null || e.id().isBlank()
                    || e.id().length() > 100 || (e.note() != null && e.note().length() > 500)) {
                throw new InvalidInputException("each piece of evidence needs a type, an id and at most 500 characters of note");
            }
        }
        MsikaPrincipal me = CurrentUser.require();
        String by = me.userId().toString();
        ZoneId zone = zone(council);
        try {
            return tenant.inCouncil(council, () -> {
                UUID before = byKey(council, by, key);
                if (before != null) {
                    return repeated(council, before, kind, subject, effect, me);
                }
                RequestEffects.Subject s = effects.describe(council, kind, subject, effect);
                boolean may = me.grants().stream().anyMatch(g -> g.role() == kind.raiser()
                        && (s.marketId() == null || g.marketIds().contains(s.marketId())));
                if (!may) {
                    throw new AccessDeniedException("not allowed to raise this here");
                }
                RequestPolicy policy = policy(council, kind);
                Instant now = clock.instant();
                UUID id = UUID.randomUUID();
                jdbc.sql("insert into request (id, council_id, kind, subject_type, subject_id, market_id, raised_by_type, "
                                + "raised_by_id, reason, proposed_effect, effect_words, status, policy, due_at, idempotency_key, "
                                + "created_at) values (:id, :c, :k, :st, :s, :m, 'USER', :by, :reason, :e::jsonb, :w, 'OPEN', "
                                + ":p::jsonb, :due, :key, :now)")
                        .param("id", id).param("c", council).param("k", kind.name()).param("st", kind.subjectType())
                        .param("s", subject).param("m", s.marketId(), java.sql.Types.OTHER).param("by", by)
                        .param("reason", reason.strip())
                        .param("e", json.writeValueAsString(effect)).param("w", s.words())
                        .param("p", json.writeValueAsString(policy))
                        .param("due", at(WorkingDays.due(now, policy.deadlineDays(), zone))).param("key", key)
                        .param("now", at(now)).update();
                for (Evidence e : evidence) {
                    jdbc.sql("insert into request_evidence (id, council_id, request_id, evidence_type, evidence_id, note) "
                                    + "values (:id, :c, :r, :t, :e, :n)").param("id", UUID.randomUUID()).param("c", council)
                            .param("r", id).param("t", e.type()).param("e", e.id()).param("n", e.note() == null ? "" : e.note())
                            .update();
                }
                audit.record(council, by, "request.raise", id + " " + kind);
                return new Raised(detail(council, id, me), true);
            });
        } catch (DuplicateKeyException e) {
            // Either the same command arrived twice at once, or somebody else asked for the same thing first.
            return tenant.inCouncil(council, () -> {
                UUID before = byKey(council, by, key);
                if (before == null) {
                    throw new ConflictException("there is already an open request for this");
                }
                return repeated(council, before, kind, subject, effect, me);
            });
        }
    }

    private UUID byKey(UUID council, String by, String key) {
        return jdbc.sql("select id from request where council_id = :c and raised_by_id = :by and idempotency_key = :k")
                .param("c", council).param("by", by).param("k", key).query(UUID.class).optional().orElse(null);
    }

    private Raised repeated(UUID council, UUID id, RequestKind kind, UUID subject, Map<String, String> effect, MsikaPrincipal me) {
        Detail d = detail(council, id, me);
        String saved = jdbc.sql("select proposed_effect::text from request where council_id = :c and id = :id").param("c", council)
                .param("id", id).query(String.class).single();
        if (d.request().kind() != kind || !d.request().subjectId().equals(subject)
                || !json.readValue(saved, new TypeReference<Map<String, String>>() {}).equals(effect)) {
            throw new ConflictException("this Idempotency-Key was used for a different request");
        }
        return new Raised(d, false);
    }

    public Detail withdraw(UUID council, UUID id) {
        MsikaPrincipal me = CurrentUser.require();
        return tenant.inCouncil(council, () -> {
            int done = jdbc.sql("update request set status = 'WITHDRAWN' where council_id = :c and id = :id and status = 'OPEN' "
                    + "and raised_by_type = 'USER' and raised_by_id = :by").param("c", council).param("id", id)
                    .param("by", me.userId().toString()).update();
            Detail d = detail(council, id, me);
            if (done == 0 && !(d.request().status().equals("WITHDRAWN") && d.request().raisedById().equals(me.userId().toString()))) {
                throw new ConflictException("only the person who raised an open request can withdraw it");
            }
            if (done == 1) {
                audit.record(council, me.userId().toString(), "request.withdraw", id.toString());
            }
            return d;
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Deciding
    // ---------------------------------------------------------------------------------------------------------

    public Detail decide(UUID council, UUID id, boolean approve, String reason, UUID corrects) {
        MsikaPrincipal me = CurrentUser.require();
        try {
            return tenant.inCouncil(council, () -> decideNow(council, id, approve, reason, corrects, me));
        } catch (EffectFailed e) {
            tenant.inCouncil(council, () -> {
                jdbc.sql("update request set last_problem = :p where council_id = :c and id = :id and status = 'OPEN'")
                        .param("p", e.getMessage()).param("c", council).param("id", id).update();
            });
            throw new ConflictException("not approved: " + e.getMessage() + ". The request stays open");
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("the database refused this decision: check who raised the request and that it is open");
        }
    }

    private Detail decideNow(UUID council, UUID id, boolean approve, String reason, UUID corrects, MsikaPrincipal me) {
        jdbc.sql("select 1 from request where council_id = :c and id = :id for update").param("c", council).param("id", id)
                .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("request not found"));
        Detail d = detail(council, id, me);
        String text = reason == null || reason.isBlank() ? null : reason.strip();
        if (text != null && text.length() > 500) {
            throw new InvalidInputException("reason must be at most 500 characters");
        }
        Decision mine = d.decisions().stream().filter(x -> x.decidedBy().equals(me.userId())).reduce((a, b) -> b).orElse(null);
        String wanted = approve ? "APPROVE" : "REJECT";
        if (corrects == null && mine != null && mine.decision().equals(wanted)) {
            return d; // the same decision sent again
        }
        if (!d.request().status().equals("OPEN")) {
            throw new ConflictException("the request is " + d.request().status());
        }
        if (corrects != null && (approve || mine == null || !mine.id().equals(corrects))) {
            throw new InvalidInputException("a correction rejects a request you approved, and names that approval");
        }
        if (corrects == null && d.request().refusal() != null) {
            throw d.request().refusal().startsWith("this approval needs") ? new AccessDeniedException("not allowed")
                    : new ConflictException(d.request().refusal());
        }
        if (text == null && (!approve || d.request().approvals() > 0)) {
            throw new InvalidInputException("a reason is required for a rejection and for a second approval");
        }
        jdbc.sql("insert into request_decision (id, council_id, request_id, decided_by, decision, reason, decided_at, "
                        + "corrects_decision_id) values (:id, :c, :r, :by, :d, :reason, :at, :corrects)")
                .param("id", UUID.randomUUID()).param("c", council).param("r", id).param("by", me.userId()).param("d", wanted)
                .param("reason", text, java.sql.Types.VARCHAR).param("at", at(clock.instant()))
                .param("corrects", corrects, java.sql.Types.OTHER).update();
        boolean last = approve && d.request().approvals() + 1 == d.request().policy().steps().size();
        if (!approve || last) {
            jdbc.sql("update request set status = :s, last_problem = null where council_id = :c and id = :id")
                    .param("s", approve ? "APPROVED" : "REJECTED").param("c", council).param("id", id).update();
        }
        if (last) {
            String effect = jdbc.sql("select proposed_effect::text from request where council_id = :c and id = :id")
                    .param("c", council).param("id", id).query(String.class).single();
            try {
                effects.run(council, d.request().kind(), d.request().subjectId(),
                        json.readValue(effect, new TypeReference<Map<String, String>>() {}));
            } catch (ConflictException | NotFoundException e) {
                throw new EffectFailed(e.getMessage());
            }
        }
        audit.record(council, me.userId().toString(), approve ? "request.approve" : "request.reject", id.toString());
        return detail(council, id, me);
    }

    /** Approves several requests of one kind, one decision for each. One that fails does not stop the others. */
    public List<BulkResult> bulkApprove(UUID council, List<UUID> ids, String reason) {
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        if (wanted.isEmpty() || wanted.size() > MAX_BULK) {
            throw new InvalidInputException("choose 1 to " + MAX_BULK + " requests");
        }
        MsikaPrincipal me = CurrentUser.require();
        List<Item> items = tenant.inCouncil(council, () -> wanted.stream().map(i -> item(council, i, me)).toList());
        if (items.stream().map(Item::kind).distinct().count() > 1) {
            throw new InvalidInputException("requests decided together must be of one kind");
        }
        if (items.stream().anyMatch(i -> !i.policy().bulkAllowed())) {
            throw new ConflictException("this kind of request cannot be approved in bulk");
        }
        int sample = items.stream().mapToInt(i -> i.policy().sampleOf(wanted.size())).max().orElse(0);
        int opened = tenant.inCouncil(council, () -> jdbc.sql("select count(*) from request_view where council_id = :c "
                        + "and user_id = :u and request_id in (:ids)").param("c", council).param("u", me.userId())
                .param("ids", wanted).query(Integer.class).single());
        if (opened < sample) {
            throw new ConflictException("open at least " + sample + " of these requests before approving them together (" + opened
                    + " opened so far)");
        }
        List<BulkResult> results = new ArrayList<>();
        for (UUID id : wanted) {
            try {
                results.add(new BulkResult(id, decide(council, id, true, reason, null).request().status(), null));
            } catch (ConflictException | InvalidInputException e) {
                results.add(new BulkResult(id, "OPEN", e.getMessage()));
            } catch (AccessDeniedException e) {
                results.add(new BulkResult(id, "OPEN", "not allowed"));
            }
        }
        return results;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------

    /** @param queue mine, raised or overdue */
    public List<Item> inbox(UUID council, String queue) {
        MsikaPrincipal me = CurrentUser.require();
        List<Item> open = tenant.inCouncil(council, () -> items(council, null, "raised".equals(queue) ? me.userId() : null, me));
        return switch (queue) {
            case "mine" -> open.stream().filter(i -> i.refusal() == null).toList();
            case "raised" -> open;
            case "overdue" -> open.stream().filter(Item::overdue).toList();
            default -> throw new InvalidInputException("queue must be mine, raised or overdue");
        };
    }

    /** One request. Opening it is remembered, for the sample a bulk approval needs. */
    public Detail open(UUID council, UUID id) {
        MsikaPrincipal me = CurrentUser.require();
        return tenant.inCouncil(council, () -> {
            Detail d = detail(council, id, me);
            if (!me.isOperator()) {
                jdbc.sql("insert into request_view (council_id, request_id, user_id, at) values (:c, :r, :u, :at) "
                                + "on conflict do nothing").param("c", council).param("r", id).param("u", me.userId())
                        .param("at", at(clock.instant())).update();
            }
            return d;
        });
    }

    private Detail detail(UUID council, UUID id, MsikaPrincipal me) {
        Item item = item(council, id, me);
        List<Evidence> evidence = jdbc.sql("select evidence_type, evidence_id, note from request_evidence where council_id = :c "
                        + "and request_id = :r order by id").param("c", council).param("r", id)
                .query((rs, n) -> new Evidence(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        List<Decision> decisions = jdbc.sql("select d.*, u.email from request_decision d join council_user u on u.id = d.decided_by "
                        + "where d.council_id = :c and d.request_id = :r order by d.decided_at, d.id").param("c", council).param("r", id)
                .query((rs, n) -> new Decision(rs.getObject("id", UUID.class), rs.getObject("decided_by", UUID.class),
                        rs.getString("email"), rs.getString("decision"), rs.getString("reason"),
                        rs.getObject("decided_at", OffsetDateTime.class).toInstant(), rs.getObject("corrects_decision_id", UUID.class)))
                .list();
        return new Detail(item, evidence, decisions);
    }

    private Item item(UUID council, UUID id, MsikaPrincipal me) {
        return items(council, id, null, me).stream().findFirst().orElseThrow(() -> new NotFoundException("request not found"));
    }

    /** One request by id, everything one user raised, or every open request; earliest deadline first. */
    private List<Item> items(UUID council, UUID id, UUID raisedBy, MsikaPrincipal me) {
        Instant now = clock.instant();
        String which = id != null ? "r.id = :id" : raisedBy != null ? "r.raised_by_type = 'USER' and r.raised_by_id = :by"
                : "r.status = 'OPEN'";
        JdbcClient.StatementSpec sql = jdbc.sql("""
                select r.*, r.policy::text as policy_text, u.email,
                       coalesce(a.n, 0) as approvals, coalesce(a.mine, false) as mine,
                       exists (select 1 from request_handover h where h.request_id = r.id and h.to_user_id = :me
                          and not exists (select 1 from request_handover l where l.request_id = r.id
                                            and l.from_user_id = :me and l.id > h.id)) as handed_to_me,
                       exists (select 1 from request_handover h where h.request_id = r.id and h.from_user_id = :me
                          and not exists (select 1 from request_handover l where l.request_id = r.id
                                            and l.to_user_id = :me and l.id > h.id)) as handed_on
                from request r
                left join council_user u on r.raised_by_type = 'USER' and u.id::text = r.raised_by_id
                left join lateral (select count(*)::int as n, bool_or(d.decided_by = :me) as mine from request_decision d
                                    where d.request_id = r.id and d.decision = 'APPROVE') a on true
                where r.council_id = :c and
                """ + which + " order by r.due_at, r.created_at, r.id")
                .param("c", council).param("me", me.userId());
        if (id != null) {
            sql = sql.param("id", id);
        } else if (raisedBy != null) {
            sql = sql.param("by", raisedBy.toString());
        }
        return sql.query((rs, n) -> {
            RequestPolicy policy = json.readValue(rs.getString("policy_text"), RequestPolicy.class);
            boolean open = rs.getString("status").equals("OPEN");
            Instant due = rs.getObject("due_at", OffsetDateTime.class).toInstant();
            boolean escalated = rs.getObject("escalated_at") != null;
            UUID market = rs.getObject("market_id", UUID.class);
            String refusal = !open ? "the request is " + rs.getString("status")
                    : rs.getBoolean("handed_on") ? "you handed this request to a colleague"
                    : DecisionRules.refusal(policy, rs.getInt("approvals"), market,
                            rs.getString("raised_by_type").equals("USER") && rs.getString("raised_by_id").equals(me.userId().toString()),
                            rs.getBoolean("mine"), escalated, me.grants());
            return new Item(rs.getObject("id", UUID.class), RequestKind.valueOf(rs.getString("kind")), rs.getString("subject_type"),
                    rs.getObject("subject_id", UUID.class), market, rs.getString("raised_by_type"), rs.getString("raised_by_id"),
                    rs.getString("email"), rs.getString("reason"), rs.getString("effect_words"), rs.getString("status"), due,
                    open && due.isBefore(now), escalated, rs.getString("last_problem"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("approvals"), policy, refusal,
                    rs.getBoolean("handed_to_me"));
        }).list();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Absence and deadlines
    // ---------------------------------------------------------------------------------------------------------

    /** Hands every open request I may decide, and the colleague may too, to that colleague. Returns how many. */
    public int handOver(UUID council, UUID to) {
        MsikaPrincipal me = CurrentUser.require();
        if (me.userId().equals(to)) {
            throw new InvalidInputException("choose a colleague");
        }
        return tenant.inCouncil(council, () -> {
            CouncilUser colleague = users.findInCouncil(council, to).filter(CouncilUser::active)
                    .orElseThrow(() -> new NotFoundException("no active user with this id"));
            MsikaPrincipal them = new MsikaPrincipal(colleague.id(), council, colleague.grants(), colleague.email());
            // A request handed on earlier is refused to that person, so one cannot bounce back by accident; taking it back
            // is a hand-over the other way, which their own queue allows.
            Set<UUID> mine = new LinkedHashSet<>();
            items(council, null, null, me).stream().filter(i -> i.refusal() == null).forEach(i -> mine.add(i.id()));
            int moved = 0;
            for (Item i : items(council, null, null, them)) {
                boolean roleHeld = i.refusal() == null || i.refusal().startsWith("you handed");
                if (mine.contains(i.id()) && roleHeld) {
                    jdbc.sql("insert into request_handover (council_id, request_id, from_user_id, to_user_id, at) "
                                    + "values (:c, :r, :f, :t, :at)").param("c", council).param("r", i.id()).param("f", me.userId())
                            .param("t", to).param("at", at(clock.instant())).update();
                    moved++;
                }
            }
            audit.record(council, me.userId().toString(), "request.handover", to + " " + moved);
            return moved;
        });
    }

    /** Marks what is overdue, so a council admin may decide it as well. Returns how many were newly marked. */
    public int escalate(UUID council) {
        return tenant.inCouncil(council, () -> {
            List<UUID> late = jdbc.sql("update request set escalated_at = :now where council_id = :c and status = 'OPEN' "
                            + "and due_at < :now and escalated_at is null returning id").param("now", at(clock.instant()))
                    .param("c", council).query(UUID.class).list();
            late.forEach(id -> audit.record(council, "system:requests", "request.escalate", id.toString()));
            return late.size();
        });
    }

    /** Looks for overdue requests every few minutes. */
    @Component
    @ConditionalOnProperty(name = "msika.requests.escalation.enabled", havingValue = "true", matchIfMissing = true)
    static class Escalation {
        private static final Logger LOG = LoggerFactory.getLogger(Escalation.class);
        private final RequestService requests;
        private final CouncilRepository councils;

        Escalation(RequestService requests, CouncilRepository councils) {
            this.requests = requests;
            this.councils = councils;
        }

        @Scheduled(cron = "${msika.requests.escalation.cron:0 */10 * * * *}")
        void run() {
            for (UUID council : councils.listIds()) {
                try {
                    requests.escalate(council);
                } catch (RuntimeException e) {
                    LOG.error("escalating requests failed for council {}", council, e);
                }
            }
        }
    }

    private ZoneId zone(UUID council) {
        return councils.find(council).orElseThrow(() -> new NotFoundException("council not found")).timezone();
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    static RequestKind kind(String name) {
        return Arrays.stream(RequestKind.values()).filter(k -> k.name().equals(name)).findFirst()
                .orElseThrow(() -> new InvalidInputException("kind must be one of " + Arrays.toString(RequestKind.values())));
    }
}
