package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallRepository;
import zm.msika.core.stall.StallStatus;

/** Keeps stalls in Postgres. */
@Repository
class JdbcStallRepository implements StallRepository {

    private static final String COLUMNS = "id, council_id, market_id, code, stall_class, status, qr_token";

    private final JdbcClient jdbc;

    JdbcStallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Called inside a transaction, so if one insert fails none of them stay. */
    @Override
    public void addAll(List<Stall> stalls) {
        for (Stall stall : stalls) {
            try {
                jdbc.sql("insert into stall (" + COLUMNS + ") values "
                                + "(:id, :councilId, :marketId, :code, :stallClass, :status, :qrToken)")
                        .param("id", stall.id())
                        .param("councilId", stall.councilId())
                        .param("marketId", stall.marketId())
                        .param("code", stall.code())
                        .param("stallClass", stall.stallClass())
                        .param("status", stall.status().name())
                        .param("qrToken", stall.qrToken())
                        .update();
            } catch (DuplicateKeyException e) {
                // Another upload created the same stall at the same moment.
                throw new ConflictException("stall code " + stall.code() + " already exists, nothing was created");
            }
        }
    }

    @Override
    public Optional<Stall> find(UUID councilId, UUID stallId) {
        return jdbc.sql("select " + COLUMNS + " from stall where council_id = :councilId and id = :id")
                .param("councilId", councilId)
                .param("id", stallId)
                .query((rs, row) -> map(rs))
                .optional();
    }

    @Override
    public List<Stall> listByMarket(UUID councilId, UUID marketId) {
        return jdbc.sql("select " + COLUMNS
                        + " from stall where council_id = :councilId and market_id = :marketId order by code")
                .param("councilId", councilId)
                .param("marketId", marketId)
                .query((rs, row) -> map(rs))
                .list();
    }

    @Override
    public Set<String> existingCodes(UUID councilId, Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select code from stall where council_id = :councilId and code in (:codes)")
                .param("councilId", councilId)
                .param("codes", codes)
                .query(String.class)
                .list());
    }

    @Override
    public void setStatus(UUID councilId, UUID stallId, StallStatus status) {
        jdbc.sql("update stall set status = :status where council_id = :councilId and id = :id")
                .param("status", status.name())
                .param("councilId", councilId)
                .param("id", stallId)
                .update();
    }

    private static Stall map(ResultSet rs) throws SQLException {
        return new Stall(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                rs.getObject("market_id", UUID.class),
                rs.getString("code"),
                rs.getString("stall_class"),
                StallStatus.valueOf(rs.getString("status")),
                rs.getString("qr_token"));
    }
}
