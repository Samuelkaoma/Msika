package zm.msika.api.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import zm.msika.api.ApiTest;

/** Market plans through the API and the database (card M-48). */
class PlanApiTest extends ApiTest {

    record MarketBody(UUID id) {}

    record CreateMarket(String code, String name, String location) {}

    record StallRow(UUID id, String code) {}

    record FootprintBody(UUID stallId, String stallCode, int[] polygonMm, String provenance, Integer uncertaintyMm,
            String aisle, String side, Integer position) {}

    record PlanBody(UUID id, UUID marketId, int version, String status, List<FootprintBody> footprints,
            List<UUID> unmappedStallIds) {}

    record Start(String from) {}

    record Edit(String notes, List<Object> sections, List<Object> aisles, List<Object> features,
            List<FootprintBody> footprints, List<UUID> unmappedStallIds) {}

    record BlockerBody(String kind, String message, List<String> stalls) {}

    record Blocked(String error, List<BlockerBody> blockers) {}

    private Fixture council;
    private UUID market;
    private List<StallRow> stalls;

    private String base() {
        return "/api/v1/councils/" + council.councilId();
    }

    @BeforeEach
    void setUp() {
        council = newCouncil();
        market = api().post(council.adminToken(), base() + "/markets", new CreateMarket("MAP", "Map Market", "Lusaka"),
                MarketBody.class).body().id();
        StringBuilder csv = new StringBuilder("section,number,class\n");
        for (int i = 0; i < 300; i++) {
            csv.append("ABCD".charAt(i % 4)).append(',').append(i / 4 + 1).append(",STANDARD\n");
        }
        assertThat(api().postCsv(council.adminToken(), base() + "/markets/" + market + "/stalls/import", csv.toString(),
                String.class).status()).isEqualTo(201);
        stalls = List.of(api().get(council.adminToken(), base() + "/markets/" + market + "/stalls", StallRow[].class).body());
        assertThat(stalls).hasSize(300);
    }

    private PlanBody start(String from) {
        var r = api().post(council.adminToken(), base() + "/markets/" + market + "/plans", new Start(from), PlanBody.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    @Test
    @DisplayName("a generated plan for a 300-stall market places every stall and publishes")
    void generateAndPublish() {
        PlanBody draft = start("GENERATED");

        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.footprints()).hasSize(300);
        assertThat(draft.unmappedStallIds()).isEmpty();
        assertThat(draft.footprints()).allMatch(f -> "GENERATED".equals(f.provenance()) && f.uncertaintyMm() == null);

        var published = api().post(council.adminToken(), base() + "/plans/" + draft.id() + "/publish", null, PlanBody.class);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.body().status()).isEqualTo("PUBLISHED");
        PlanBody current = api().get(council.officerToken(), base() + "/markets/" + market + "/plan", PlanBody.class).body();
        assertThat(current.id()).isEqualTo(draft.id());
        assertThat(current.footprints()).extracting(FootprintBody::stallCode).doesNotContainNull().hasSize(300);
    }

    @Test
    @DisplayName("a draft with four different problems is refused with 422 listing all four")
    void fourBlockers() {
        PlanBody draft = start("GENERATED");
        List<FootprintBody> fps = new ArrayList<>(draft.footprints());
        // 1. a bow tie; 2. two stalls on top of each other; 3. a stall of another market; 4. one stall left out.
        FootprintBody first = fps.get(0);
        fps.set(0, new FootprintBody(first.stallId(), null, new int[] {0, 0, 2000, 2000, 2000, 0, 0, 2000}, "TRACED", 100,
                first.aisle(), first.side(), first.position()));
        FootprintBody second = fps.get(1);
        FootprintBody third = fps.get(2);
        fps.set(2, new FootprintBody(third.stallId(), null, second.polygonMm(), "GENERATED", null, third.aisle(), third.side(),
                third.position()));
        UUID otherMarket = api().post(council.adminToken(), base() + "/markets", new CreateMarket("OTH", "Other Market", "Lusaka"),
                MarketBody.class).body().id();
        api().postCsv(council.adminToken(), base() + "/markets/" + otherMarket + "/stalls/import",
                "section,number,class\nZ,1,STANDARD\n", String.class);
        UUID elsewhere = api().get(council.adminToken(), base() + "/markets/" + otherMarket + "/stalls", StallRow[].class).body()[0].id();
        fps.add(new FootprintBody(elsewhere, null, new int[] {900000, 0, 902500, 0, 902500, 2500, 900000, 2500},
                "GENERATED", null, null, null, null));
        String leftOut = fps.remove(10).stallCode();
        var edited = api().put(council.adminToken(), base() + "/plans/" + draft.id(),
                new Edit("broken on purpose", List.of(), List.of(), List.of(), fps, List.of()), PlanBody.class);
        assertThat(edited.status()).isEqualTo(200);

        var refused = api().post(council.adminToken(), base() + "/plans/" + draft.id() + "/publish", null, Blocked.class);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().blockers()).extracting(BlockerBody::kind)
                .containsExactlyInAnyOrder("BAD_POLYGON", "OVERLAP", "UNKNOWN_STALL", "MISSING_STALL");
        assertThat(refused.body().blockers()).filteredOn(b -> b.kind().equals("MISSING_STALL")).singleElement()
                .satisfies(b -> assertThat(b.stalls()).containsExactly(leftOut));
        assertThat(api().get(council.adminToken(), base() + "/plans/" + draft.id(), PlanBody.class).body().status())
                .isEqualTo("DRAFT");
        // A stall the council does not have at all is refused when the draft is saved, not stored.
        fps.add(new FootprintBody(UUID.randomUUID(), null, new int[] {0, 0, 1, 0, 1, 1}, "GENERATED", null, null, null, null));
        assertThat(api().put(council.adminToken(), base() + "/plans/" + draft.id(),
                new Edit(null, List.of(), List.of(), List.of(), fps, List.of()), String.class).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a published plan is frozen: the API refuses an edit, and the database refuses an UPDATE or DELETE")
    void frozen() {
        PlanBody draft = start("GENERATED");
        api().post(council.adminToken(), base() + "/plans/" + draft.id() + "/publish", null, PlanBody.class);

        assertThat(api().put(council.adminToken(), base() + "/plans/" + draft.id(),
                new Edit(null, List.of(), List.of(), List.of(), List.of(), List.of()), String.class).status()).isEqualTo(409);
        assertThatThrownBy(() -> ownerJdbc().sql("update stall_footprint set polygon_mm = '{0,0,1,0,1,1}' where plan_id = :p")
                .param("p", draft.id()).update()).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> ownerJdbc().sql("delete from stall_footprint where plan_id = :p")
                .param("p", draft.id()).update()).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> ownerJdbc().sql("update market_plan set notes = 'x' where id = :p")
                .param("p", draft.id()).update()).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> ownerJdbc().sql("delete from market_plan where id = :p")
                .param("p", draft.id()).update()).hasMessageContaining("cannot be deleted");
    }

    @Test
    @DisplayName("publishing a new version retires the old one, which stays readable")
    void versions() {
        PlanBody v1 = start("GENERATED");
        api().post(council.adminToken(), base() + "/plans/" + v1.id() + "/publish", null, PlanBody.class);
        PlanBody v2 = start("COPY");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.footprints()).hasSize(300);

        api().post(council.adminToken(), base() + "/plans/" + v2.id() + "/publish", null, PlanBody.class);

        assertThat(api().get(council.adminToken(), base() + "/plans/" + v1.id(), PlanBody.class).body().status()).isEqualTo("RETIRED");
        assertThat(api().get(council.adminToken(), base() + "/markets/" + market + "/plan", PlanBody.class).body().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("an officer reads plans but cannot start one; another council cannot see them")
    void access() {
        PlanBody draft = start("EMPTY");
        assertThat(api().post(council.officerToken(), base() + "/markets/" + market + "/plans", new Start("EMPTY"),
                String.class).status()).isEqualTo(403);
        assertThat(api().get(council.officerToken(), base() + "/plans/" + draft.id(), String.class).status()).isEqualTo(200);
        Fixture other = newCouncil();
        assertThat(api().get(other.adminToken(), base() + "/plans/" + draft.id(), String.class).status()).isEqualTo(403);
        assertThat(api().send(other.adminToken(), HttpMethod.GET, "/api/v1/councils/" + other.councilId() + "/plans/" + draft.id(),
                null, MediaType.APPLICATION_JSON, String.class, null).status()).isEqualTo(404);
    }
}
