package zm.msika.api.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.api.ledger.SealRows;
import zm.msika.core.ledger.SealRepository;
import zm.msika.core.ledger.SealedDay;
import zm.msika.ledger.DailySeal;

/** Keeps daily seals in Postgres. Seals are only added; the database refuses to change or remove one. */
@Repository
class JdbcSealRepository implements SealRepository {

    private static final String COLUMNS = SealRows.COLUMNS;

    private final JdbcClient jdbc;

    JdbcSealRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(UUID councilId, SealedDay sealed) {
        DailySeal seal = sealed.seal();
        jdbc.sql("insert into daily_seal (" + COLUMNS + ") values "
                        + "(:councilId, :date, :count, :root, :previous, :algorithm, :signature, :keyId, :sealedAt)")
                .param("councilId", councilId)
                .param("date", seal.businessDate())
                .param("count", seal.entryCount())
                .param("root", seal.merkleRoot())
                .param("previous", seal.previousSealHash())
                .param("algorithm", seal.algorithm())
                .param("signature", seal.signature())
                .param("keyId", sealed.keyId())
                .param("sealedAt", OffsetDateTime.ofInstant(sealed.sealedAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<SealedDay> find(UUID councilId, LocalDate businessDate) {
        return jdbc.sql("select " + COLUMNS + " from daily_seal where council_id = :councilId and business_date = :date")
                .param("councilId", councilId)
                .param("date", businessDate)
                .query((rs, row) -> SealRows.map(rs))
                .optional();
    }

    @Override
    public Optional<SealedDay> latest(UUID councilId) {
        return jdbc.sql("select " + COLUMNS + " from daily_seal where council_id = :councilId "
                        + "order by business_date desc limit 1")
                .param("councilId", councilId)
                .query((rs, row) -> SealRows.map(rs))
                .optional();
    }

    @Override
    public List<SealedDay> list(UUID councilId) {
        return jdbc.sql("select " + COLUMNS + " from daily_seal where council_id = :councilId order by business_date")
                .param("councilId", councilId)
                .query((rs, row) -> SealRows.map(rs))
                .list();
    }
}
