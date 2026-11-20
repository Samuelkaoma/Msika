package zm.msika.api.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.stall.TraderDirectory;

/** Answers whether a trader exists in a council. Registering traders is card M-10. */
@Repository
class JdbcTraderDirectory implements TraderDirectory {

    private final JdbcClient jdbc;

    JdbcTraderDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID councilId, UUID traderId) {
        return jdbc.sql("select count(*) from trader where council_id = :councilId and id = :id")
                .param("councilId", councilId)
                .param("id", traderId)
                .query(Integer.class)
                .single() > 0;
    }
}
