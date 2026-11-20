package zm.msika.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.PostgresIntegrationTest;
import zm.msika.api.ledger.LedgerTestSupport.Ledger;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Provenance;

/**
 * Recording ledger entries against a real Postgres, and the proof that the ledger cannot be changed
 * (AGENTS.md rules 2 and 5).
 */
class RecordingAndImmutabilityTest extends PostgresIntegrationTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    private static final Instant TWO_WEEKS_AGO = Instant.now().minusSeconds(14 * 86_400L);

    private Ledger newLedger() {
        return LedgerTestSupport.newLedger(jdbc, tenant, TWO_WEEKS_AGO);
    }

    private int rowsFor(UUID council, String key) {
        return tenant.inCouncil(council, () -> jdbc
                .sql("select count(*) from ledger_entry where council_id = :c and idempotency_key = :k")
                .param("c", council).param("k", key).query(Integer.class).single());
    }

    // ------------------------------------------------------------- idempotency

    @Test
    @DisplayName("recording the same idempotency key twice gives one entry")
    void sameKeyTwiceIsOneEntry() {
        Ledger l = newLedger();
        NewEntry request = l.deposit("dep-1", 45_000, Instant.now());

        RecordEntry.Recorded first = ledger.record(request);
        RecordEntry.Recorded second = ledger.record(request);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.entry().id()).isEqualTo(first.entry().id());
        assertThat(rowsFor(l.councilId(), "dep-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("eight threads recording the same key at once still give exactly one entry, round after round")
    void concurrentSameKey() throws Exception {
        Ledger l = newLedger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // Many rounds, each with a fresh key: a race between two inserts of one key is a matter of timing,
            // so one round can pass by luck. Twenty rounds of eight make it show up every time.
            for (int round = 0; round < 20; round++) {
                String key = "race-" + round;
                NewEntry request = l.deposit(key, 12_300 + round, Instant.now());
                List<Callable<RecordEntry.Recorded>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(() -> ledger.record(request));
                }
                List<Future<RecordEntry.Recorded>> results = pool.invokeAll(tasks);

                List<Long> ids = new ArrayList<>();
                int firsts = 0;
                for (Future<RecordEntry.Recorded> f : results) {
                    RecordEntry.Recorded recorded = f.get();
                    ids.add(recorded.entry().id());
                    if (!recorded.duplicate()) {
                        firsts++;
                    }
                }
                assertThat(firsts).as("round %d: exactly one call actually recorded it", round).isEqualTo(1);
                assertThat(ids).as("round %d: every call got the same entry back", round).containsOnly(ids.get(0));
                assertThat(rowsFor(l.councilId(), key)).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the same key with a different amount is a conflict and changes nothing")
    void sameKeyDifferentContent() {
        Ledger l = newLedger();
        ledger.record(l.deposit("dep-1", 45_000, Instant.parse("2026-11-20T08:00:00Z")));

        assertThatThrownBy(() -> ledger.record(l.deposit("dep-1", 45_001, Instant.parse("2026-11-20T08:00:00Z"))))
                .isInstanceOf(ConflictException.class);

        long amount = tenant.inCouncil(l.councilId(), () -> jdbc
                .sql("select amount_ngwee from ledger_entry where council_id = :c and idempotency_key = 'dep-1'")
                .param("c", l.councilId()).query(Long.class).single());
        assertThat(amount).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("the same key in two councils is two entries")
    void keysArePerCouncil() {
        Ledger a = newLedger();
        Ledger b = newLedger();

        RecordEntry.Recorded first = ledger.record(a.deposit("dep-1", 100, Instant.now()));
        RecordEntry.Recorded second = ledger.record(b.deposit("dep-1", 100, Instant.now()));

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isFalse();
    }

    @Test
    @DisplayName("an unknown stall is not found, and nothing is recorded")
    void unknownStall() {
        Ledger l = newLedger();
        NewEntry request = new NewEntry(l.councilId(), UUID.randomUUID(), EntryKind.DEPOSIT, Provenance.DEPOSITED,
                zm.msika.ledger.Money.ofMinor(100, zm.msika.ledger.Currency.ZMW), Instant.now(), "k", null);

        assertThatThrownBy(() -> ledger.record(request)).isInstanceOf(NotFoundException.class);
        assertThat(rowsFor(l.councilId(), "k")).isZero();
    }

    @Test
    @DisplayName("amounts are exact whole ngwee in a bigint, with no rounding even at the largest sizes")
    void amountsAreExact() {
        Ledger l = newLedger();
        long huge = 9_007_199_254_740_993L; // 2^53 + 1: a double cannot hold this exactly, a bigint can

        LedgerEntry entry = ledger.record(l.deposit("huge", huge, Instant.now())).entry();

        long stored = tenant.inCouncil(l.councilId(), () -> jdbc
                .sql("select amount_ngwee from ledger_entry where id = :id").param("id", entry.id())
                .query(Long.class).single());
        assertThat(stored).isEqualTo(huge);
        assertThat(entry.amount().minorUnits()).isEqualTo(huge);
    }

    // ------------------------------------------------------------ business date

    @Test
    @DisplayName("the business date is the Lusaka date: 21:59 UTC is still the day, 22:00 UTC is the next")
    void lusakaMidnight() {
        Ledger l = newLedger();
        Instant before = Instant.now().minusSeconds(3 * 86_400L).atZone(java.time.ZoneOffset.UTC)
                .withHour(21).withMinute(59).withSecond(59).withNano(0).toInstant();
        Instant after = before.plusSeconds(1);

        LedgerEntry a = ledger.record(l.deposit("a", 100, before)).entry();
        LedgerEntry b = ledger.record(l.deposit("b", 100, after)).entry();

        assertThat(b.businessDate()).isEqualTo(a.businessDate().plusDays(1));
    }

    @Test
    @DisplayName("an entry for a day that is already sealed goes to the next open day")
    void afterSealingGoesToNextDay() {
        Ledger l = newLedger();
        LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Africa/Lusaka")).minusDays(1);
        ledger.closeDays(l.councilId(), yesterday, "test");

        LedgerEntry late = ledger.record(l.deposit("late", 100, Instant.now().minusSeconds(2 * 86_400L))).entry();

        assertThat(late.businessDate()).isEqualTo(yesterday.plusDays(1));
    }

    // ---------------------------------------------------------------- reversals

    @Test
    @DisplayName("a mistake is fixed with a reversal: both stay on the ledger and the original is untouched")
    void reversalKeepsBothEntries() {
        Ledger l = newLedger();
        LedgerEntry original = ledger.record(l.deposit("dep-1", 45_000, Instant.now())).entry();

        LedgerEntry reversal = ledger.record(l.reversalOf(original, "rev-1", Instant.now())).entry();

        assertThat(reversal.amount().minorUnits()).isEqualTo(-45_000L);
        assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
        long total = tenant.inCouncil(l.councilId(), () -> jdbc
                .sql("select sum(amount_ngwee) from ledger_entry where council_id = :c").param("c", l.councilId())
                .query(Long.class).single());
        assertThat(total).as("the two entries net to zero").isZero();
        assertThatThrownBy(() -> ledger.record(l.reversalOf(original, "rev-2", Instant.now())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already been reversed");
    }

    // ----------------------------------------------- the database enforces it

    private void recordOne(Ledger l) {
        ledger.record(l.deposit("seed", 5_000, Instant.now()));
    }

    @Test
    @DisplayName("the app role cannot UPDATE, DELETE or TRUNCATE the ledger: the database says permission denied")
    void appRoleCannotChangeTheLedger() {
        Ledger l = newLedger();
        recordOne(l);

        for (String sql : new String[] {
            "update ledger_entry set amount_ngwee = 1",
            "delete from ledger_entry",
            "truncate ledger_entry",
            "update daily_seal set entry_count = 99",
            "delete from daily_seal",
            "truncate daily_seal"}) {
            assertThatThrownBy(() -> tenant.inCouncil(l.councilId(), () -> jdbc.sql(sql).update()))
                    .as(sql).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    @DisplayName("even the table's owner cannot UPDATE or DELETE a ledger row: a trigger refuses it")
    void ownerCannotChangeTheLedgerEither() {
        Ledger l = newLedger();
        recordOne(l);
        JdbcClient owner = ownerJdbc();

        assertThatThrownBy(() -> owner.sql("update ledger_entry set amount_ngwee = 1 where council_id = :c")
                .param("c", l.councilId()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.sql("delete from ledger_entry where council_id = :c")
                .param("c", l.councilId()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.sql("truncate ledger_entry").update())
                .rootCause().hasMessageContaining("append-only");
        assertThat(rowsFor(l.councilId(), "seed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a raw insert into a sealed day is refused by the database, even from the owner, bypassing RecordEntry")
    void sealedDayCannotReceiveEntries() {
        Ledger l = newLedger();
        LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Africa/Lusaka")).minusDays(1);
        ledger.closeDays(l.councilId(), yesterday, "test");

        assertThatThrownBy(() -> ownerJdbc().sql("insert into ledger_entry (council_id, stall_id, kind, provenance, "
                        + "amount_ngwee, business_date, occurred_at, idempotency_key) values "
                        + "(:c, :s, 'DEPOSIT', 'DEPOSITED', 100, :d, now(), 'sneaky')")
                .param("c", l.councilId()).param("s", l.stallId()).param("d", yesterday).update())
                .rootCause().hasMessageContaining("already sealed");
    }

    @Test
    @DisplayName("the database refuses impossible entries even from the owner: a declared sale cannot be VERIFIED")
    void databaseChecksMirrorTheDomainRules() {
        Ledger l = newLedger();
        String insert = "insert into ledger_entry (council_id, stall_id, kind, provenance, amount_ngwee, "
                + "business_date, occurred_at, idempotency_key, reverses_entry_id) values "
                + "(:c, :s, :kind, :prov, :amount, current_date, now(), :key, null)";

        assertThatThrownBy(() -> ownerJdbc().sql(insert).param("c", l.councilId()).param("s", l.stallId())
                .param("kind", "DECLARED_SALE").param("prov", "VERIFIED").param("amount", 100L).param("key", "x1")
                .update()).as("declared sale marked verified").rootCause().hasMessageContaining("check constraint");
        assertThatThrownBy(() -> ownerJdbc().sql(insert).param("c", l.councilId()).param("s", l.stallId())
                .param("kind", "DEPOSIT").param("prov", "DEPOSITED").param("amount", -100L).param("key", "x2")
                .update()).as("negative deposit").rootCause().hasMessageContaining("check constraint");
        assertThatThrownBy(() -> ownerJdbc().sql(insert).param("c", l.councilId()).param("s", l.stallId())
                .param("kind", "REVERSAL").param("prov", "DEPOSITED").param("amount", -100L).param("key", "x3")
                .update()).as("reversal that reverses nothing").rootCause().hasMessageContaining("check constraint");
    }

    @Test
    @DisplayName("a second reversal of the same entry is refused by a unique index, even if RecordEntry were bypassed")
    void databaseAllowsOneReversalPerEntry() {
        Ledger l = newLedger();
        LedgerEntry original = ledger.record(l.deposit("dep-1", 700, Instant.now())).entry();
        ledger.record(l.reversalOf(original, "rev-1", Instant.now()));

        assertThatThrownBy(() -> ownerJdbc().sql("insert into ledger_entry (council_id, stall_id, kind, provenance, "
                        + "amount_ngwee, business_date, occurred_at, idempotency_key, reverses_entry_id) values "
                        + "(:c, :s, 'REVERSAL', 'DEPOSITED', -700, current_date, now(), 'rev-2', :orig)")
                .param("c", l.councilId()).param("s", l.stallId()).param("orig", original.id()).update())
                .rootCause().hasMessageContaining("ledger_entry_reversed_once");
    }
}
