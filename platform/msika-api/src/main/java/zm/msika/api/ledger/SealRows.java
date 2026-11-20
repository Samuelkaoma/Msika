package zm.msika.api.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import zm.msika.core.ledger.SealedDay;
import zm.msika.ledger.DailySeal;

/** Turns a {@code daily_seal} row into a {@link SealedDay}. Shared by the repository and the seal verifier. */
public final class SealRows {

    /** The columns {@link #map} reads. */
    public static final String COLUMNS =
            "council_id, business_date, entry_count, merkle_root, prev_seal_hash, algorithm, signature, key_id, sealed_at";

    private SealRows() {}

    public static SealedDay map(ResultSet rs) throws SQLException {
        DailySeal seal = new DailySeal(
                rs.getObject("council_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                rs.getInt("entry_count"),
                rs.getBytes("merkle_root"),
                rs.getBytes("prev_seal_hash"),
                rs.getString("algorithm"),
                rs.getBytes("signature"));
        return new SealedDay(seal, rs.getString("key_id"), rs.getObject("sealed_at", OffsetDateTime.class).toInstant());
    }
}
