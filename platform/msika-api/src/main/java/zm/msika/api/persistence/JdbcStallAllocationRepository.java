package zm.msika.api.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.stall.StallAllocation;
import zm.msika.core.stall.StallAllocationRepository;

/** Keeps stall allocations in Postgres. Ended allocations stay as history. */
@Repository
class JdbcStallAllocationRepository implements StallAllocationRepository {

    private final JdbcClient jdbc;

    JdbcStallAllocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(StallAllocation allocation) {
        try {
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from, valid_to) "
                            + "values (:id, :councilId, :stallId, :traderId, :validFrom, null)")
                    .param("id", allocation.id())
                    .param("councilId", allocation.councilId())
                    .param("stallId", allocation.stallId())
                    .param("traderId", allocation.traderId())
                    .param("validFrom", OffsetDateTime.ofInstant(allocation.validFrom(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            // The one-active-allocation-per-stall index refused a second one.
            throw new ConflictException("the stall already has an active allocation");
        }
    }

    @Override
    public Optional<StallAllocation> findActive(UUID councilId, UUID stallId) {
        return jdbc.sql("select id, council_id, stall_id, trader_id, valid_from from stall_allocation "
                        + "where council_id = :councilId and stall_id = :stallId and valid_to is null")
                .param("councilId", councilId)
                .param("stallId", stallId)
                .query((rs, row) -> new StallAllocation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("council_id", UUID.class),
                        rs.getObject("stall_id", UUID.class),
                        rs.getObject("trader_id", UUID.class),
                        rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        null))
                .optional();
    }

    @Override
    public void end(UUID councilId, UUID allocationId, Instant at) {
        jdbc.sql("update stall_allocation set valid_to = :at where council_id = :councilId and id = :id")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("councilId", councilId)
                .param("id", allocationId)
                .update();
    }
}
