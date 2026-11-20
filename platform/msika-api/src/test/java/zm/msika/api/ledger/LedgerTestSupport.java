package zm.msika.api.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.ledger.NewEntry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * Builds a council, a market and a stall directly in the database, so a test can have a council that was
 * created days ago (the HTTP API can only create one that is created now) and record entries against it.
 */
final class LedgerTestSupport {

    /** A council with one stall, ready for entries. */
    record Ledger(UUID councilId, UUID stallId) {

        NewEntry deposit(String key, long ngwee, Instant at) {
            return new NewEntry(councilId, stallId, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    Money.ofMinor(ngwee, Currency.ZMW), at, key, null);
        }

        NewEntry declared(String key, long ngwee, Instant at) {
            return new NewEntry(councilId, stallId, EntryKind.DECLARED_SALE, Provenance.DECLARED,
                    Money.ofMinor(ngwee, Currency.ZMW), at, key, null);
        }

        NewEntry reversalOf(zm.msika.ledger.LedgerEntry target, String key, Instant at) {
            return new NewEntry(councilId, target.stallId(), EntryKind.REVERSAL, target.provenance(),
                    target.amount().negate(), at, key, target.id());
        }
    }

    private LedgerTestSupport() {}

    /** @param createdAt when the council was created; sealing starts on this day (in Lusaka time) */
    static Ledger newLedger(JdbcClient jdbc, TenantContext tenant, Instant createdAt) {
        UUID council = UUID.randomUUID();
        UUID market = UUID.randomUUID();
        UUID stall = UUID.randomUUID();
        jdbc.sql("insert into council (id, name, timezone, created_at) values (:id, :name, 'Africa/Lusaka', :at)")
                .param("id", council)
                .param("name", "Ledger Test " + council)
                .param("at", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
        tenant.inCouncil(council, () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'LDG', 'Ledger Market', '20:00', now())")
                    .param("id", market).param("c", council).update();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'LDG-A-1', 'STANDARD', 'VACANT', :token)")
                    .param("id", stall).param("c", council).param("m", market)
                    .param("token", UUID.randomUUID().toString().replace("-", "")).update();
        });
        return new Ledger(council, stall);
    }
}
