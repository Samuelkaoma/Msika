package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.phone.Language;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.trader.Trader;
import zm.msika.core.trader.TraderRepository;

/** Keeps traders in Postgres. The database refuses a phone number that is not E.164. */
@Repository
class JdbcTraderRepository implements TraderRepository {

    private static final String COLUMNS =
            "id, council_id, msisdn, display_name, language, consent_levy_sweep_at, consent_data_at, created_at";

    private final JdbcClient jdbc;

    JdbcTraderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(Trader trader) {
        try {
            jdbc.sql("insert into trader (" + COLUMNS + ") values "
                            + "(:id, :councilId, :msisdn, :name, :language, :levy, :data, :createdAt)")
                    .param("id", trader.id())
                    .param("councilId", trader.councilId())
                    .param("msisdn", trader.phone().e164())
                    .param("name", trader.displayName())
                    .param("language", trader.language().code())
                    .param("levy", trader.consentLevySweepAt() == null ? null
                            : OffsetDateTime.ofInstant(trader.consentLevySweepAt(), ZoneOffset.UTC))
                    .param("data", trader.consentDataAt() == null ? null
                            : OffsetDateTime.ofInstant(trader.consentDataAt(), ZoneOffset.UTC))
                    .param("createdAt", OffsetDateTime.ofInstant(trader.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            // Two requests registered the same number at once; the unique constraint caught the second.
            throw new ConflictException("a trader with this phone number is already registered");
        }
    }

    @Override
    public List<Trader> list(UUID councilId) {
        return jdbc.sql("select " + COLUMNS + " from trader where council_id = :councilId order by display_name, id")
                .param("councilId", councilId)
                .query((rs, row) -> map(rs))
                .list();
    }

    @Override
    public boolean existsByPhone(UUID councilId, PhoneNumber phone) {
        return jdbc.sql("select count(*) from trader where council_id = :councilId and msisdn = :msisdn")
                .param("councilId", councilId)
                .param("msisdn", phone.e164())
                .query(Integer.class)
                .single() > 0;
    }

    private static Trader map(ResultSet rs) throws SQLException {
        OffsetDateTime levy = rs.getObject("consent_levy_sweep_at", OffsetDateTime.class);
        OffsetDateTime data = rs.getObject("consent_data_at", OffsetDateTime.class);
        return new Trader(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                new PhoneNumber(rs.getString("msisdn")),
                rs.getString("display_name"),
                Language.fromCode(rs.getString("language")),
                levy == null ? null : levy.toInstant(),
                data == null ? null : data.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
