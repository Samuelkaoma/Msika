package zm.msika.api.map;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.security.CurrentUser;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.map.LayoutGenerator;
import zm.msika.core.map.MarketPlan;
import zm.msika.core.map.PointMm;
import zm.msika.core.map.PolygonMm;

/**
 * Market plans over HTTP (card M-48, MARKET-MAP section 11). Polygons travel as flat arrays {@code [x1, y1, x2, y2,
 * ...]} of integer millimetres. Paths sit under the council, like every council resource, so the usual rule applies:
 * any user of the council may read, only non-officers may change.
 */
@RestController
class PlanController {

    record SectionBody(String name, int[] polygonMm) {}

    record AisleBody(String name, int[] pathMm) {}

    record FeatureBody(String kind, String label, int[] shapeMm) {}

    record FootprintBody(UUID stallId, String stallCode, int[] polygonMm, String provenance, Integer uncertaintyMm,
            String aisle, String side, Integer position) {}

    record PlanBody(UUID id, UUID marketId, int version, String status, String notes, Instant publishedAt,
            List<SectionBody> sections, List<AisleBody> aisles, List<FeatureBody> features, List<FootprintBody> footprints,
            List<UUID> unmappedStallIds) {}

    record PlanSummary(UUID id, int version, String status, Instant publishedAt) {}

    record StartRequest(String from) {}

    record EditRequest(String notes, List<SectionBody> sections, List<AisleBody> aisles, List<FeatureBody> features,
            List<FootprintBody> footprints, List<UUID> unmappedStallIds) {}

    record ConfigBody(int stallsPerSide, int stallWidthMm, int stallDepthMm, int aisleWidthMm, int sectionGapMm) {}

    record BlockerBody(String kind, String message, List<String> stalls) {}

    record Blocked(String error, String message, List<BlockerBody> blockers) {}

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    private PlanBody body(UUID councilId, PlanService.Plan p) {
        Map<UUID, String> codes = plans.stallCodes(councilId, p.marketId());
        MarketPlan c = p.content();
        return new PlanBody(p.id(), p.marketId(), p.version(), p.status(), p.notes(), p.publishedAt(),
                c.sections().stream().map(s -> new SectionBody(s.name(), s.polygon().toFlat())).toList(),
                c.aisles().stream().map(a -> new AisleBody(a.name(), flat(a.path()))).toList(),
                c.features().stream().map(f -> new FeatureBody(f.kind().name(), f.label(), f.shape().toFlat())).toList(),
                c.footprints().stream().map(f -> new FootprintBody(f.stallId(), codes.get(f.stallId()), f.polygon().toFlat(),
                        f.provenance().name(), f.uncertaintyMm(), f.aisle(), f.side() == null ? null : f.side().name(),
                        f.position() == 0 ? null : f.position())).toList(),
                c.unmappedStallIds().stream().sorted().toList());
    }

    private static int[] flat(List<PointMm> points) {
        return PolygonMm.of(points).toFlat();
    }

    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/plan")
    PlanBody current(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        return body(councilId, plans.current(councilId, marketId)
                .orElseThrow(() -> new NotFoundException("this market has no published plan yet")));
    }

    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/plans")
    List<PlanSummary> versions(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        return plans.versions(councilId, marketId).stream()
                .map(p -> new PlanSummary(p.id(), p.version(), p.status(), p.publishedAt())).toList();
    }

    @PostMapping("/api/v1/councils/{councilId}/markets/{marketId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    PlanBody start(@PathVariable UUID councilId, @PathVariable UUID marketId, @RequestBody StartRequest r) {
        PlanService.StartFrom from;
        try {
            from = PlanService.StartFrom.valueOf(r.from() == null ? "EMPTY" : r.from().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("from must be EMPTY, GENERATED or COPY");
        }
        return body(councilId, plans.startDraft(councilId, marketId, from, CurrentUser.actor()));
    }

    @GetMapping("/api/v1/councils/{councilId}/plans/{planId}")
    PlanBody plan(@PathVariable UUID councilId, @PathVariable UUID planId) {
        return body(councilId, plans.plan(councilId, planId));
    }

    @PutMapping("/api/v1/councils/{councilId}/plans/{planId}")
    PlanBody edit(@PathVariable UUID councilId, @PathVariable UUID planId, @RequestBody EditRequest r) {
        return body(councilId, plans.replaceDraft(councilId, planId, content(r), r.notes(), CurrentUser.actor()));
    }

    @PostMapping("/api/v1/councils/{councilId}/plans/{planId}/publish")
    PlanBody publish(@PathVariable UUID councilId, @PathVariable UUID planId) {
        return body(councilId, plans.publish(councilId, planId, CurrentUser.actor()));
    }

    @GetMapping("/api/v1/councils/{councilId}/markets/{marketId}/layout-config")
    ConfigBody config(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        LayoutGenerator.Config c = plans.config(councilId, marketId);
        return new ConfigBody(c.stallsPerSide(), c.stallWidthMm(), c.stallDepthMm(), c.aisleWidthMm(), c.sectionGapMm());
    }

    @PutMapping("/api/v1/councils/{councilId}/markets/{marketId}/layout-config")
    ConfigBody setConfig(@PathVariable UUID councilId, @PathVariable UUID marketId, @RequestBody ConfigBody b) {
        LayoutGenerator.Config c;
        try {
            c = new LayoutGenerator.Config(b.stallsPerSide(), b.stallWidthMm(), b.stallDepthMm(), b.aisleWidthMm(), b.sectionGapMm());
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(e.getMessage());
        }
        plans.setConfig(councilId, marketId, c, CurrentUser.actor());
        return b;
    }

    /** Every reason a plan cannot be published, at once: 422 with the list (MARKET-MAP section 4). */
    @ExceptionHandler(PlanService.Blocked.class)
    ResponseEntity<Blocked> blocked(PlanService.Blocked e) {
        return ResponseEntity.unprocessableContent().body(new Blocked("plan_blocked", e.getMessage(),
                e.blockers().stream().map(b -> new BlockerBody(b.kind().name(), b.message(), b.stalls())).toList()));
    }

    private static MarketPlan content(EditRequest r) {
        try {
            List<MarketPlan.Section> sections = new ArrayList<>();
            for (SectionBody s : orEmpty(r.sections())) {
                sections.add(new MarketPlan.Section(s.name(), PolygonMm.fromFlat(s.polygonMm())));
            }
            List<MarketPlan.Aisle> aisles = new ArrayList<>();
            for (AisleBody a : orEmpty(r.aisles())) {
                aisles.add(new MarketPlan.Aisle(a.name(), PolygonMm.fromFlat(a.pathMm()).corners()));
            }
            List<MarketPlan.Feature> features = new ArrayList<>();
            for (FeatureBody f : orEmpty(r.features())) {
                features.add(new MarketPlan.Feature(MarketPlan.FeatureKind.valueOf(f.kind()), f.label(), PolygonMm.fromFlat(f.shapeMm())));
            }
            List<MarketPlan.Footprint> footprints = new ArrayList<>();
            for (FootprintBody f : orEmpty(r.footprints())) {
                footprints.add(new MarketPlan.Footprint(f.stallId(), PolygonMm.fromFlat(f.polygonMm()),
                        MarketPlan.Provenance.valueOf(f.provenance()), f.uncertaintyMm(), f.aisle(),
                        f.side() == null ? null : MarketPlan.Side.valueOf(f.side()), f.position() == null ? 0 : f.position()));
            }
            Set<UUID> unmapped = new HashSet<>(orEmpty(r.unmappedStallIds()));
            return new MarketPlan(sections, aisles, features, footprints, unmapped);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidInputException("the plan is not shaped right: " + e.getMessage());
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
