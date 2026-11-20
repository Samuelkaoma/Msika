package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.market.Market;
import zm.msika.core.market.MarketRepository;

/** Keeps markets in Postgres. */
@Repository
class JdbcMarketRepository implements MarketRepository {

    private static final String COLUMNS = "id, council_id, code, name, location, day_close_time, created_at";

    private final JdbcClient jdbc;

    JdbcMarketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(Market market) {
        try {
            jdbc.sql("insert into market (" + COLUMNS + ") values "
                            + "(:id, :councilId, :code, :name, :location, :dayCloseTime, :createdAt)")
                    .param("id", market.id())
                    .param("councilId", market.councilId())
                    .param("code", market.code())
                    .param("name", market.name())
                    .param("location", market.location())
                    .param("dayCloseTime", market.dayCloseTime())
                    .param("createdAt", OffsetDateTime.ofInstant(market.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            // Two requests created the same code at once; the unique constraint caught the second.
            throw new ConflictException("a market with code " + market.code() + " already exists");
        }
    }

    @Override
    public Optional<Market> find(UUID councilId, UUID marketId) {
        return jdbc.sql("select " + COLUMNS + " from market where council_id = :councilId and id = :id")
                .param("councilId", councilId)
                .param("id", marketId)
                .query((rs, row) -> map(rs))
                .optional();
    }

    @Override
    public List<Market> list(UUID councilId) {
        return jdbc.sql("select " + COLUMNS + " from market where council_id = :councilId order by code")
                .param("councilId", councilId)
                .query((rs, row) -> map(rs))
                .list();
    }

    @Override
    public boolean existsByCode(UUID councilId, String code) {
        return jdbc.sql("select count(*) from market where council_id = :councilId and code = :code")
                .param("councilId", councilId)
                .param("code", code)
                .query(Integer.class)
                .single() > 0;
    }

    private static Market map(ResultSet rs) throws SQLException {
        return new Market(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("location"),
                rs.getObject("day_close_time", LocalTime.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
