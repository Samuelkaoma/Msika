package zm.msika.api.map;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;

/** The live closing view (card M-51): the deposit feed of a market, and an agent's round. */
@RestController
class LiveController {

    /** An agent's round tonight: the stalls they deposited for, and the held stalls in their aisles not yet reached. */
    record Round(UUID agentId, LocalDate date, List<String> aisles, List<UUID> done, List<UUID> notReached) {}

    private final MapEvents events;
    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final CouncilRepository councils;
    private final Clock clock;

    LiveController(MapEvents events, TenantContext tenant, JdbcClient jdbc, CouncilRepository councils, Clock clock) {
        this.events = events;
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.councils = councils;
        this.clock = clock;
    }

    @GetMapping(path = "/api/v1/councils/{councilId}/markets/{marketId}/map-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter mapEvents(@PathVariable UUID councilId, @PathVariable UUID marketId) {
        boolean ours = tenant.inCouncil(councilId, () -> jdbc.sql("select exists (select 1 from market where council_id = :c and id = :m)")
                .param("c", councilId).param("m", marketId).query(Boolean.class).single());
        if (!ours) {
            throw new NotFoundException("no market " + marketId + " in this council");
        }
        return events.watch(councilId, marketId);
    }

    @GetMapping("/api/v1/councils/{councilId}/agents/{agentId}/round")
    Round round(@PathVariable UUID councilId, @PathVariable UUID agentId, @RequestParam("marketId") UUID marketId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : clock.instant().atZone(councils.find(councilId).orElseThrow().timezone()).toLocalDate();
        return tenant.inCouncil(councilId, () -> {
            List<UUID> done = jdbc.sql("select distinct d.stall_id from deposit d join stall s on s.id = d.stall_id "
                            + "where d.council_id = :c and d.agent_id = :a and s.market_id = :m and d.business_date = :day "
                            + "and d.status in ('PENDING', 'CONFIRMED', 'DISPUTED')")
                    .param("c", councilId).param("a", agentId).param("m", marketId).param("day", day).query(UUID.class).list();
            List<String> aisles = done.isEmpty() ? List.of() : jdbc.sql("select distinct f.aisle from stall_footprint f "
                            + "join market_plan p on p.id = f.plan_id where f.council_id = :c and p.market_id = :m "
                            + "and p.status = 'PUBLISHED' and f.stall_id in (:done) and f.aisle is not null order by f.aisle")
                    .param("c", councilId).param("m", marketId).param("done", done).query(String.class).list();
            List<UUID> notReached = aisles.isEmpty() ? List.of() : jdbc.sql("""
                            select f.stall_id from stall_footprint f
                            join market_plan p on p.id = f.plan_id
                            join stall s on s.id = f.stall_id
                            where f.council_id = :c and p.market_id = :m and p.status = 'PUBLISHED' and f.aisle in (:aisles)
                              and s.status = 'ALLOCATED'
                              and not exists (select 1 from deposit d where d.council_id = :c and d.stall_id = f.stall_id
                                              and d.business_date = :day and d.status in ('PENDING', 'CONFIRMED', 'DISPUTED'))
                            order by f.aisle, f.side, f.position
                            """)
                    .param("c", councilId).param("m", marketId).param("aisles", aisles).param("day", day).query(UUID.class).list();
            return new Round(agentId, day, aisles, done, notReached);
        });
    }
}
