package zm.msika.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.PostgresIntegrationTest;
import zm.msika.api.ledger.LedgerTestSupport.Ledger;
import zm.msika.api.seal.SealKeys;
import zm.msika.api.seal.SealVerifier;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.ledger.SealedDay;
import zm.msika.ledger.EcdsaSignatureScheme;

/** Closing trading days against a real Postgres: sealing, safe repeats, concurrency, and the scheduled job. */
class DayCloseTest extends PostgresIntegrationTest {

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    CouncilRepository councils;

    @Autowired
    SealKeys keys;

    private static LocalDate today() {
        return LocalDate.now(LUSAKA);
    }

    /** A council that was created {@code daysAgo} days ago, at midday Lusaka time. */
    private Ledger councilFrom(int daysAgo) {
        Instant created = today().minusDays(daysAgo).atTime(12, 0).atZone(LUSAKA).toInstant();
        return LedgerTestSupport.newLedger(jdbc, tenant, created);
    }

    private SealVerifier.Report verify(Ledger l) {
        return new SealVerifier(ownerJdbc(), new EcdsaSignatureScheme()).verify(keys.publicKey(), l.councilId());
    }

    private int sealCount(Ledger l) {
        return ownerJdbc().sql("select count(*) from daily_seal where council_id = :c")
                .param("c", l.councilId()).query(Integer.class).single();
    }

    private Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(LUSAKA).toInstant();
    }

    @Test
    @DisplayName("closing seals every day from the council's first day, each seal verifies, and the chain has no gap")
    void sealsDaysAndChainVerifies() {
        Ledger l = councilFrom(4);
        ledger.record(l.deposit("d1", 10_000, at(today().minusDays(3), 10)));
        ledger.record(l.deposit("d2", 20_000, at(today().minusDays(3), 15)));
        ledger.record(l.deposit("d3", 30_000, at(today().minusDays(1), 9)));

        List<SealedDay> sealed = ledger.closeDays(l.councilId(), today().minusDays(1), "test");

        assertThat(sealed).extracting(s -> s.seal().businessDate())
                .containsExactly(today().minusDays(4), today().minusDays(3), today().minusDays(2), today().minusDays(1));
        assertThat(sealed.stream().map(s -> s.seal().entryCount())).containsExactly(0, 2, 0, 1);
        assertThat(sealed).allSatisfy(s -> assertThat(s.keyId()).isEqualTo(keys.keyId()));
        SealVerifier.Report report = verify(l);
        assertThat(report.problems()).isEmpty();
        assertThat(report.seals()).isEqualTo(4);
        assertThat(report.entries()).isEqualTo(3);
    }

    @Test
    @DisplayName("running day close twice is safe: the second run seals nothing and the first seals are untouched")
    void safeToRunTwice() {
        Ledger l = councilFrom(2);
        ledger.record(l.deposit("a", 10_000, at(today().minusDays(1), 10)));
        ledger.closeDays(l.councilId(), today().minusDays(1), "test");
        List<String> before = signatures(l);

        List<SealedDay> again = ledger.closeDays(l.councilId(), today().minusDays(1), "test");

        assertThat(again).isEmpty();
        assertThat(signatures(l)).as("not a byte of any seal changed").isEqualTo(before);
        assertThat(sealCount(l)).isEqualTo(2);
        assertThat(verify(l).ok()).isTrue();
    }

    private List<String> signatures(Ledger l) {
        return ownerJdbc().sql("select encode(signature, 'hex') || encode(merkle_root, 'hex') from daily_seal "
                        + "where council_id = :c order by business_date")
                .param("c", l.councilId()).query(String.class).list();
    }

    @Test
    @DisplayName("four closes at the same moment give exactly one seal per day, with no error")
    void concurrentCloses() throws Exception {
        Ledger l = councilFrom(3);
        ledger.record(l.deposit("a", 10_000, at(today().minusDays(2), 10)));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> ledger.closeDays(l.councilId(), today().minusDays(1), "test").size());
            }
            int sealedInTotal = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                sealedInTotal += f.get();
            }

            assertThat(sealedInTotal).as("each day was sealed by exactly one of them").isEqualTo(3);
            assertThat(sealCount(l)).isEqualTo(3);
            assertThat(verify(l).ok()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("recording while days are being closed: nothing is lost, nothing lands in a sealed day, and every seal verifies")
    void recordingWhileClosing() throws Exception {
        Ledger l = councilFrom(1);
        int recorders = 4;
        int perRecorder = 40;
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean recording = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(recorders + 1);
        try {
            List<Future<?>> recordersDone = new ArrayList<>();
            for (int r = 0; r < recorders; r++) {
                int recorder = r;
                recordersDone.add(pool.submit(() -> {
                    for (int i = 0; i < perRecorder; i++) {
                        try {
                            ledger.record(l.deposit("r" + recorder + "-" + i, 100 + i, Instant.now()));
                        } catch (Throwable t) {
                            failures.add(t);
                        }
                    }
                }));
            }
            Future<?> closer = pool.submit(() -> {
                while (recording.get()) {
                    try {
                        ledger.closeDays(l.councilId(), today(), "test");
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }
            });
            for (Future<?> f : recordersDone) {
                f.get();
            }
            recording.set(false);
            closer.get();
            ledger.closeDays(l.councilId(), today(), "test");

            assertThat(failures).as("no recorder or closer failed").isEmpty();
            int stored = ownerJdbc().sql("select count(*) from ledger_entry where council_id = :c")
                    .param("c", l.councilId()).query(Integer.class).single();
            assertThat(stored).as("every entry was recorded exactly once").isEqualTo(recorders * perRecorder);
            SealVerifier.Report report = verify(l);
            assertThat(report.problems()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the scheduled job closes yesterday and earlier before the close time, and today too after it")
    void scheduledJob() {
        Ledger l = councilFrom(3);
        LocalDate d = today();
        Clock morning = Clock.fixed(d.atTime(10, 0).atZone(LUSAKA).toInstant(), LUSAKA);
        Clock evening = Clock.fixed(d.atTime(20, 30).atZone(LUSAKA).toInstant(), LUSAKA);

        new DayCloseJob(councils, ledger, morning, LocalTime.of(20, 0)).closeFor(l.councilId());
        assertThat(sealCount(l)).as("10:00: days up to yesterday").isEqualTo(3);

        new DayCloseJob(councils, ledger, morning, LocalTime.of(20, 0)).closeFor(l.councilId());
        assertThat(sealCount(l)).as("a second run at the same time changes nothing").isEqualTo(3);

        new DayCloseJob(councils, ledger, evening, LocalTime.of(20, 0)).closeFor(l.councilId());
        assertThat(sealCount(l)).as("20:30: today too").isEqualTo(4);
        assertThat(verify(l).ok()).isTrue();
    }

    @Test
    @DisplayName("a day close writes an audit row naming who asked")
    void closeIsAudited() {
        Ledger l = councilFrom(1);

        ledger.closeDays(l.councilId(), today().minusDays(1), "system:day-close");
        ledger.closeDays(l.councilId(), today().minusDays(1), "someone-else");

        List<String> rows = ownerJdbc().sql("select actor || ' ' || action || ' ' || target from audit_log "
                        + "where council_id = :c and action = 'day.close'")
                .param("c", l.councilId()).query(String.class).list();
        assertThat(rows).as("one row, for the call that actually sealed something").hasSize(1);
        assertThat(rows.get(0)).startsWith("system:day-close day.close");
    }
}
