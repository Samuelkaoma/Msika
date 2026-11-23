package zm.msika.api.map;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.map.LayoutGenerator;
import zm.msika.core.map.MarketPlan;
import zm.msika.core.map.PlanCheck;
import zm.msika.core.map.PointMm;
import zm.msika.core.map.PolygonMm;

/**
 * Market plans (card M-48): start a draft (empty, generated from the stall codes, or a copy of the current plan),
 * replace a draft's contents, publish it (every blocker listed at once), and read plans. Everything runs as one
 * council. A published plan is frozen by the database (migration V15).
 */
@Service
public class PlanService {

    /** A plan and what it is. */
    public record Plan(UUID id, UUID marketId, int version, String status, String notes, Instant publishedAt,
            MarketPlan content) {}

    /** Publishing was refused; every reason is listed. */
    public static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<PlanCheck.Blocker> blockers;

        Blocked(List<PlanCheck.Blocker> blockers) {
            super(blockers.size() + " problem(s) stop this plan being published", null, false, false);
            this.blockers = List.copyOf(blockers);
        }

        public List<PlanCheck.Blocker> blockers() {
            return blockers;
        }
    }

    public enum StartFrom {
        EMPTY, GENERATED, COPY
    }

    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Clock clock;

    PlanService(TenantContext tenant, JdbcClient jdbc, AuditLog audit, Clock clock) {
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------------------------------------- config

    public LayoutGenerator.Config config(UUID councilId, UUID marketId) {
        return tenant.inCouncil(councilId, () -> configIn(councilId, marketId));
    }

    private LayoutGenerator.Config configIn(UUID councilId, UUID marketId) {
        requireMarket(councilId, marketId);
        return jdbc.sql("select stalls_per_side, stall_width_mm, stall_depth_mm, aisle_width_mm, section_gap_mm "
                        + "from market_layout_config where council_id = :c and market_id = :m")
                .param("c", councilId).param("m", marketId)
                .query((rs, n) -> new LayoutGenerator.Config(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
                .optional().orElse(LayoutGenerator.Config.standard());
    }

    public LayoutGenerator.Config setConfig(UUID councilId, UUID marketId, LayoutGenerator.Config c, String actor) {
        tenant.inCouncil(councilId, () -> {
            requireMarket(councilId, marketId);
            jdbc.sql("insert into market_layout_config (council_id, market_id, stalls_per_side, stall_width_mm, stall_depth_mm, "
                            + "aisle_width_mm, section_gap_mm) values (:c, :m, :s, :w, :d, :a, :g) on conflict (council_id, market_id) "
                            + "do update set stalls_per_side = excluded.stalls_per_side, stall_width_mm = excluded.stall_width_mm, "
                            + "stall_depth_mm = excluded.stall_depth_mm, aisle_width_mm = excluded.aisle_width_mm, "
                            + "section_gap_mm = excluded.section_gap_mm")
                    .param("c", councilId).param("m", marketId).param("s", c.stallsPerSide()).param("w", c.stallWidthMm())
                    .param("d", c.stallDepthMm()).param("a", c.aisleWidthMm()).param("g", c.sectionGapMm()).update();
            audit.record(councilId, actor, "plan.layout-config", marketId.toString());
        });
        return c;
    }

    // ------------------------------------------------------------------------------------------------- drafts

    public Plan startDraft(UUID councilId, UUID marketId, StartFrom from, String actor) {
        return tenant.inCouncil(councilId, () -> {
            requireMarket(councilId, marketId);
            MarketPlan content = switch (from) {
                case EMPTY -> MarketPlan.empty();
                case GENERATED -> LayoutGenerator.generate(stallsOf(councilId, marketId), configIn(councilId, marketId));
                case COPY -> currentIn(councilId, marketId).map(Plan::content)
                        .orElseThrow(() -> new NotFoundException("this market has no published plan to copy"));
            };
            int version = jdbc.sql("select coalesce(max(version), 0) + 1 from market_plan where council_id = :c and market_id = :m")
                    .param("c", councilId).param("m", marketId).query(Integer.class).single();
            UUID id = UUID.randomUUID();
            jdbc.sql("insert into market_plan (id, council_id, market_id, version, status, created_at, created_by) "
                            + "values (:id, :c, :m, :v, 'DRAFT', :at, :by)")
                    .param("id", id).param("c", councilId).param("m", marketId).param("v", version)
                    .param("at", utc(clock.instant())).param("by", actor).update();
            writeContent(councilId, id, content);
            audit.record(councilId, actor, "plan.draft", marketId + " v" + version + " from " + from);
            return planIn(councilId, id);
        });
    }

    public Plan replaceDraft(UUID councilId, UUID planId, MarketPlan content, String notes, String actor) {
        return tenant.inCouncil(councilId, () -> {
            Plan p = planIn(councilId, planId);
            if (!"DRAFT".equals(p.status())) {
                throw new ConflictException("plan version " + p.version() + " is " + p.status() + " and cannot be changed; start a new draft");
            }
            for (String table : List.of("stall_footprint", "plan_section", "plan_aisle", "plan_feature")) {
                jdbc.sql("delete from " + table + " where council_id = :c and plan_id = :p")
                        .param("c", councilId).param("p", planId).update();
            }
            writeContent(councilId, planId, content);
            jdbc.sql("update market_plan set notes = :n where id = :p").param("n", notes).param("p", planId).update();
            audit.record(councilId, actor, "plan.edit", planId.toString());
            return planIn(councilId, planId);
        });
    }

    /**
     * Publishes a draft: it becomes the market's plan and the previous one is retired.
     *
     * @throws Blocked with every problem found, if there are any
     */
    public Plan publish(UUID councilId, UUID planId, String actor) {
        return tenant.inCouncil(councilId, () -> {
            Plan p = planIn(councilId, planId);
            if (!"DRAFT".equals(p.status())) {
                throw new ConflictException("plan version " + p.version() + " is already " + p.status());
            }
            List<PlanCheck.Blocker> blockers = PlanCheck.check(p.content(), stallsOf(councilId, p.marketId()),
                    PlanCheck.DEFAULT_OVERLAP_TOLERANCE_MM);
            if (!blockers.isEmpty()) {
                throw new Blocked(blockers);
            }
            Instant now = clock.instant();
            jdbc.sql("update market_plan set status = 'RETIRED' where council_id = :c and market_id = :m and status = 'PUBLISHED'")
                    .param("c", councilId).param("m", p.marketId()).update();
            jdbc.sql("update market_plan set status = 'PUBLISHED', published_at = :at, published_by = :by where id = :p")
                    .param("at", utc(now)).param("by", actor).param("p", planId).update();
            audit.record(councilId, actor, "plan.publish", p.marketId() + " v" + p.version());
            return planIn(councilId, planId);
        });
    }

    // ------------------------------------------------------------------------------------------------- reading

    public Plan plan(UUID councilId, UUID planId) {
        return tenant.inCouncil(councilId, () -> planIn(councilId, planId));
    }

    public Optional<Plan> current(UUID councilId, UUID marketId) {
        return tenant.inCouncil(councilId, () -> currentIn(councilId, marketId));
    }

    /** Every version of a market's plan, newest first, without contents. */
    public List<Plan> versions(UUID councilId, UUID marketId) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("select id, market_id, version, status, notes, published_at "
                        + "from market_plan where council_id = :c and market_id = :m order by version desc")
                .param("c", councilId).param("m", marketId)
                .query((rs, n) -> new Plan(rs.getObject("id", UUID.class), rs.getObject("market_id", UUID.class),
                        rs.getInt("version"), rs.getString("status"), rs.getString("notes"),
                        Optional.ofNullable(rs.getObject("published_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant)
                                .orElse(null), null))
                .list());
    }

    /** Stall codes of the market, by id, read as the council. */
    public Map<UUID, String> stallCodes(UUID councilId, UUID marketId) {
        return tenant.inCouncil(councilId, () -> stallsOf(councilId, marketId));
    }

    /** Stall codes of the market, by id. Call inside a transaction for the council. */
    Map<UUID, String> stallsOf(UUID councilId, UUID marketId) {
        Map<UUID, String> stalls = new LinkedHashMap<>();
        jdbc.sql("select id, code from stall where council_id = :c and market_id = :m order by code")
                .param("c", councilId).param("m", marketId)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("code")))
                .list().forEach(e -> stalls.put(e.getKey(), e.getValue()));
        return stalls;
    }

    private Optional<Plan> currentIn(UUID councilId, UUID marketId) {
        return jdbc.sql("select id from market_plan where council_id = :c and market_id = :m and status = 'PUBLISHED'")
                .param("c", councilId).param("m", marketId).query(UUID.class).optional()
                .map(id -> planIn(councilId, id));
    }

    private void requireMarket(UUID councilId, UUID marketId) {
        boolean exists = jdbc.sql("select exists (select 1 from market where council_id = :c and id = :m)")
                .param("c", councilId).param("m", marketId).query(Boolean.class).single();
        if (!exists) {
            throw new NotFoundException("no market " + marketId + " in this council");
        }
    }

    private Plan planIn(UUID councilId, UUID planId) {
        record Head(UUID marketId, int version, String status, String notes, Instant publishedAt, Set<UUID> unmapped) {}
        Head h = jdbc.sql("select market_id, version, status, notes, published_at, unmapped_stall_ids from market_plan "
                        + "where council_id = :c and id = :p")
                .param("c", councilId).param("p", planId)
                .query((rs, n) -> new Head(rs.getObject("market_id", UUID.class), rs.getInt("version"), rs.getString("status"),
                        rs.getString("notes"),
                        Optional.ofNullable(rs.getObject("published_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant)
                                .orElse(null),
                        new HashSet<>(Arrays.asList((UUID[]) rs.getArray("unmapped_stall_ids").getArray()))))
                .optional().orElseThrow(() -> new NotFoundException("no plan " + planId));
        List<MarketPlan.Section> sections = jdbc.sql("select name, polygon_mm from plan_section where plan_id = :p order by name")
                .param("p", planId)
                .query((rs, n) -> new MarketPlan.Section(rs.getString("name"), PolygonMm.fromFlat(ints(rs.getArray("polygon_mm")))))
                .list();
        List<MarketPlan.Aisle> aisles = jdbc.sql("select name, path_mm from plan_aisle where plan_id = :p order by name")
                .param("p", planId)
                .query((rs, n) -> new MarketPlan.Aisle(rs.getString("name"), points(ints(rs.getArray("path_mm")))))
                .list();
        List<MarketPlan.Feature> features = jdbc.sql("select kind, label, shape_mm from plan_feature where plan_id = :p order by kind, label")
                .param("p", planId)
                .query((rs, n) -> new MarketPlan.Feature(MarketPlan.FeatureKind.valueOf(rs.getString("kind")), rs.getString("label"),
                        PolygonMm.fromFlat(ints(rs.getArray("shape_mm")))))
                .list();
        List<MarketPlan.Footprint> footprints = jdbc.sql("select stall_id, polygon_mm, provenance, uncertainty_mm, aisle, side, "
                        + "position from stall_footprint where plan_id = :p order by aisle, side, position, stall_id")
                .param("p", planId)
                .query((rs, n) -> new MarketPlan.Footprint(rs.getObject("stall_id", UUID.class),
                        PolygonMm.fromFlat(ints(rs.getArray("polygon_mm"))),
                        MarketPlan.Provenance.valueOf(rs.getString("provenance")), rs.getObject("uncertainty_mm", Integer.class),
                        rs.getString("aisle"), rs.getString("side") == null ? null : MarketPlan.Side.valueOf(rs.getString("side")),
                        rs.getObject("position", Integer.class) == null ? 0 : rs.getInt("position")))
                .list();
        return new Plan(planId, h.marketId(), h.version(), h.status(), h.notes(), h.publishedAt(),
                new MarketPlan(sections, aisles, features, footprints, h.unmapped()));
    }

    private void writeContent(UUID councilId, UUID planId, MarketPlan content) {
        // A footprint for a stall of another market is the publish check's to report; one for a stall that does not
        // exist in the council at all cannot even be stored.
        List<UUID> named = content.footprints().stream().map(MarketPlan.Footprint::stallId).distinct().toList();
        if (!named.isEmpty()) {
            long known = jdbc.sql("select count(*) from stall where council_id = :c and id in (:ids)")
                    .param("c", councilId).param("ids", named).query(Long.class).single();
            if (known != named.size()) {
                throw new InvalidInputException((named.size() - known) + " footprint(s) name a stall this council does not have");
            }
        }
        for (MarketPlan.Section s : content.sections()) {
            jdbc.sql("insert into plan_section (id, council_id, plan_id, name, polygon_mm) values (:id, :c, :p, :n, :poly)")
                    .param("id", UUID.randomUUID()).param("c", councilId).param("p", planId).param("n", s.name())
                    .param("poly", s.polygon().toFlat()).update();
        }
        for (MarketPlan.Aisle a : content.aisles()) {
            jdbc.sql("insert into plan_aisle (id, council_id, plan_id, name, path_mm) values (:id, :c, :p, :n, :path)")
                    .param("id", UUID.randomUUID()).param("c", councilId).param("p", planId).param("n", a.name())
                    .param("path", flat(a.path())).update();
        }
        for (MarketPlan.Feature f : content.features()) {
            jdbc.sql("insert into plan_feature (id, council_id, plan_id, kind, label, shape_mm) values (:id, :c, :p, :k, :l, :s)")
                    .param("id", UUID.randomUUID()).param("c", councilId).param("p", planId).param("k", f.kind().name())
                    .param("l", f.label()).param("s", f.shape().toFlat()).update();
        }
        for (MarketPlan.Footprint f : content.footprints()) {
            if (f.provenance() == MarketPlan.Provenance.GENERATED && f.uncertaintyMm() != null
                    || f.provenance() != MarketPlan.Provenance.GENERATED && f.uncertaintyMm() == null) {
                throw new InvalidInputException("a GENERATED footprint has no uncertainty, and any other must have one");
            }
            boolean addressed = f.aisle() != null && f.side() != null && f.position() > 0;
            jdbc.sql("insert into stall_footprint (id, council_id, plan_id, stall_id, polygon_mm, provenance, uncertainty_mm, "
                            + "aisle, side, position) values (:id, :c, :p, :s, :poly, :prov, :u, :aisle, :side, :pos)")
                    .param("id", UUID.randomUUID()).param("c", councilId).param("p", planId).param("s", f.stallId())
                    .param("poly", f.polygon().counterClockwise().toFlat()).param("prov", f.provenance().name())
                    .param("u", f.uncertaintyMm()).param("aisle", addressed ? f.aisle() : null)
                    .param("side", addressed ? f.side().name() : null).param("pos", addressed ? f.position() : null).update();
        }
        jdbc.sql("update market_plan set unmapped_stall_ids = :u where id = :p")
                .param("u", content.unmappedStallIds().toArray(new UUID[0])).param("p", planId).update();
    }

    private static int[] ints(Array array) throws SQLException {
        Integer[] boxed = (Integer[]) array.getArray();
        int[] out = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            out[i] = boxed[i];
        }
        return out;
    }

    private static List<PointMm> points(int[] xy) {
        List<PointMm> out = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            out.add(new PointMm(xy[i], xy[i + 1]));
        }
        return out;
    }

    private static int[] flat(List<PointMm> points) {
        int[] xy = new int[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            xy[2 * i] = points.get(i).x();
            xy[2 * i + 1] = points.get(i).y();
        }
        return xy;
    }
}
