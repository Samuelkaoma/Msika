package zm.msika.api.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * Turns a {@code ledger_entry} row into a {@link LedgerEntry}. Shared by the repository and the seal
 * verifier, so both rebuild an entry in exactly the same way (which is what the seal's hash depends on).
 */
public final class LedgerRows {

    /** The columns {@link #map} reads. */
    public static final String COLUMNS =
            "id, council_id, stall_id, kind, provenance, amount_ngwee, business_date, occurred_at, "
                    + "idempotency_key, reverses_entry_id";

    private LedgerRows() {}

    public static LedgerEntry map(ResultSet rs) throws SQLException {
        long reverses = rs.getLong("reverses_entry_id");
        Long reversesEntryId = rs.wasNull() ? null : reverses;
        return new LedgerEntry(
                rs.getLong("id"),
                rs.getObject("council_id", UUID.class),
                rs.getObject("stall_id", UUID.class),
                EntryKind.valueOf(rs.getString("kind")),
                Provenance.valueOf(rs.getString("provenance")),
                Money.ofMinor(rs.getLong("amount_ngwee"), Currency.ZMW),
                rs.getObject("business_date", LocalDate.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("idempotency_key"),
                reversesEntryId);
    }
}
