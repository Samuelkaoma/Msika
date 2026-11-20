package zm.msika.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.PostgresIntegrationTest;
import zm.msika.api.ledger.LedgerTestSupport.Ledger;
import zm.msika.api.seal.SealKeys;
import zm.msika.api.seal.SealVerifier;
import zm.msika.api.seal.VerifySealsCli;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.ledger.DailySeal;
import zm.msika.ledger.EcdsaSignatureScheme;
import zm.msika.ledger.LedgerEntry;

/**
 * The independent check (layer 3) must notice a hand-edited database. Each test below is someone who gets
 * round the application AND the database triggers (by switching the triggers off as the table's owner) and
 * then edits history. The verifier has to catch every one.
 */
class SealVerifierTest extends PostgresIntegrationTest {

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    SealKeys keys;

    private final EcdsaSignatureScheme scheme = new EcdsaSignatureScheme();

    private static LocalDate today() {
        return LocalDate.now(LUSAKA);
    }

    private Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(LUSAKA).toInstant();
    }

    /** A council with entries on three past days, all sealed. Returns the entry ids by name. */
    private record Sealed(Ledger l, LedgerEntry day1a, LedgerEntry day1b, LedgerEntry day2, LedgerEntry declared) {}

    private Sealed sealedLedger() {
        Instant created = today().minusDays(3).atTime(12, 0).atZone(LUSAKA).toInstant();
        Ledger l = LedgerTestSupport.newLedger(jdbc, tenant, created);
        LedgerEntry a = ledger.record(l.deposit("a", 10_000, at(today().minusDays(2), 9))).entry();
        LedgerEntry b = ledger.record(l.deposit("b", 20_000, at(today().minusDays(2), 11))).entry();
        LedgerEntry c = ledger.record(l.deposit("c", 30_000, at(today().minusDays(1), 10))).entry();
        LedgerEntry d = ledger.record(l.declared("d", 5_000, at(today().minusDays(1), 12))).entry();
        ledger.closeDays(l.councilId(), today().minusDays(1), "test");
        return new Sealed(l, a, b, c, d);
    }

    private SealVerifier.Report verify(Ledger l) {
        return new SealVerifier(ownerJdbc(), scheme).verify(keys.publicKey(), l.councilId());
    }

    /** Switches a trigger off (as the table's owner), runs the edit, and always switches it back on. */
    private void withTriggerOff(String table, String trigger, Runnable edit) {
        JdbcClient owner = ownerJdbc();
        owner.sql("alter table " + table + " disable trigger " + trigger).update();
        try {
            edit.run();
        } finally {
            owner.sql("alter table " + table + " enable trigger " + trigger).update();
        }
    }

    private void assertTampered(Ledger l, String expectedInProblem) {
        SealVerifier.Report report = verify(l);
        assertThat(report.ok()).as("the verifier must notice. Problems: " + report.problems()).isFalse();
        assertThat(String.join("\n", report.problems())).contains(expectedInProblem);
    }

    // ------------------------------------------------------------------ honest

    @Test
    @DisplayName("an untouched ledger verifies: every seal, every link, every entry")
    void honestLedgerPasses() {
        Sealed s = sealedLedger();

        SealVerifier.Report report = verify(s.l());

        assertThat(report.problems()).isEmpty();
        assertThat(report.seals()).isEqualTo(3);
        assertThat(report.entries()).isEqualTo(4);
        assertThat(report.ok()).isTrue();
    }

    // ------------------------------------------------------- hand-edited ledger

    @Test
    @DisplayName("changing one entry's amount is caught, and the problem names the council and the day")
    void editedAmount() {
        Sealed s = sealedLedger();

        withTriggerOff("ledger_entry", "ledger_entry_append_only", () -> ownerJdbc()
                .sql("update ledger_entry set amount_ngwee = amount_ngwee + 1 where id = :id")
                .param("id", s.day2().id()).update());

        assertTampered(s.l(), today().minusDays(1) + ": the entries do not match the sealed root");
        assertThat(verify(s.l()).problems().toString()).contains(s.l().councilId().toString());
    }

    @Test
    @DisplayName("deleting an entry is caught")
    void deletedEntry() {
        Sealed s = sealedLedger();

        withTriggerOff("ledger_entry", "ledger_entry_append_only", () -> ownerJdbc()
                .sql("delete from ledger_entry where id = :id").param("id", s.day1b().id()).update());

        assertTampered(s.l(), today().minusDays(2) + ": the seal covers 2 entries but there are 1");
    }

    @Test
    @DisplayName("slipping an extra entry into a sealed day is caught")
    void addedEntry() {
        Sealed s = sealedLedger();

        withTriggerOff("ledger_entry", "ledger_entry_sealed_day", () -> ownerJdbc()
                .sql("insert into ledger_entry (council_id, stall_id, kind, provenance, amount_ngwee, business_date, "
                        + "occurred_at, idempotency_key) values (:c, :s, 'DEPOSIT', 'DEPOSITED', 100, :d, now(), 'sneaky')")
                .param("c", s.l().councilId()).param("s", s.l().stallId()).param("d", today().minusDays(2)).update());

        assertTampered(s.l(), today().minusDays(2) + ": the seal covers 2 entries but there are 3");
    }

    @Test
    @DisplayName("moving an entry to another day (a reordering) is caught on both days")
    void movedEntry() {
        Sealed s = sealedLedger();

        withTriggerOff("ledger_entry", "ledger_entry_append_only", () -> ownerJdbc()
                .sql("update ledger_entry set business_date = :d where id = :id")
                .param("d", today().minusDays(1)).param("id", s.day1a().id()).update());

        SealVerifier.Report report = verify(s.l());
        assertThat(report.ok()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains(today().minusDays(2).toString()));
        assertThat(report.problems()).anyMatch(p -> p.contains(today().minusDays(1).toString()));
    }

    @Test
    @DisplayName("turning a declared sale into a verified merchant sale (promoting it) is caught")
    void declaredMadeVerified() {
        Sealed s = sealedLedger();

        withTriggerOff("ledger_entry", "ledger_entry_append_only", () -> ownerJdbc()
                .sql("update ledger_entry set kind = 'MERCHANT_SALE', provenance = 'VERIFIED' where id = :id")
                .param("id", s.declared().id()).update());

        assertTampered(s.l(), today().minusDays(1) + ": the entries do not match the sealed root");
    }

    @Test
    @DisplayName("an entry dated before the council's first seal is caught")
    void entryBeforeTheFirstSeal() {
        Sealed s = sealedLedger();

        withTriggerOff("ledger_entry", "ledger_entry_sealed_day", () -> ownerJdbc()
                .sql("insert into ledger_entry (council_id, stall_id, kind, provenance, amount_ngwee, business_date, "
                        + "occurred_at, idempotency_key) values (:c, :s, 'DEPOSIT', 'DEPOSITED', 100, :d, now(), 'old')")
                .param("c", s.l().councilId()).param("s", s.l().stallId()).param("d", today().minusDays(30)).update());

        assertTampered(s.l(), "dated before the first seal");
    }

    // ------------------------------------------------------- hand-edited seals

    @Test
    @DisplayName("changing a seal's stored root is caught")
    void editedSealRoot() {
        Sealed s = sealedLedger();

        withTriggerOff("daily_seal", "daily_seal_append_only", () -> ownerJdbc()
                .sql("update daily_seal set merkle_root = decode(repeat('ab', 32), 'hex') "
                        + "where council_id = :c and business_date = :d")
                .param("c", s.l().councilId()).param("d", today().minusDays(2)).update());

        assertTampered(s.l(), today().minusDays(2).toString());
    }

    @Test
    @DisplayName("deleting a seal from the middle of the chain is caught")
    void deletedSeal() {
        Sealed s = sealedLedger();

        withTriggerOff("daily_seal", "daily_seal_append_only", () -> ownerJdbc()
                .sql("delete from daily_seal where council_id = :c and business_date = :d")
                .param("c", s.l().councilId()).param("d", today().minusDays(2)).update());

        SealVerifier.Report report = verify(s.l());
        assertThat(report.ok()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("there is no seal for " + today().minusDays(2)));
    }

    @Test
    @DisplayName("an attacker who edits an entry and recomputes AND re-signs the seal with their own key is still caught")
    void resignedWithTheWrongKey() {
        Sealed s = sealedLedger();
        KeyPair attacker = scheme.generateKeyPair();
        LocalDate day = today().minusDays(1);

        withTriggerOff("ledger_entry", "ledger_entry_append_only", () -> {
            ownerJdbc().sql("update ledger_entry set amount_ngwee = 1 where id = :id").param("id", s.day2().id()).update();
            List<LedgerEntry> entries = ownerJdbc().sql("select " + LedgerRows.COLUMNS + " from ledger_entry "
                            + "where council_id = :c and business_date = :d order by id")
                    .param("c", s.l().councilId()).param("d", day).query((rs, row) -> LedgerRows.map(rs)).list();
            byte[] previous = ownerJdbc().sql("select prev_seal_hash from daily_seal where council_id = :c and business_date = :d")
                    .param("c", s.l().councilId()).param("d", day).query(byte[].class).single();
            DailySeal forged = DailySeal.seal(s.l().councilId(), day, entries, previous, scheme, attacker.getPrivate());
            withTriggerOff("daily_seal", "daily_seal_append_only", () -> ownerJdbc()
                    .sql("update daily_seal set merkle_root = :root, signature = :sig where council_id = :c and business_date = :d")
                    .param("root", forged.merkleRoot()).param("sig", forged.signature())
                    .param("c", s.l().councilId()).param("d", day).update());
        });

        assertTampered(s.l(), "signature");
    }

    @Test
    @DisplayName("checking with the wrong public key fails every seal")
    void wrongPublicKey() {
        Sealed s = sealedLedger();
        KeyPair other = scheme.generateKeyPair();

        SealVerifier.Report report = new SealVerifier(ownerJdbc(), scheme).verify(other.getPublic(), s.l().councilId());

        assertThat(report.ok()).isFalse();
        assertThat(report.problems()).isNotEmpty();
    }

    // ----------------------------------------------------- the command line tool

    private record Run(int exitCode, String out, String err) {}

    private Run cli(Ledger l, Map<String, String> env, String... extraArgs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of(
                "--url", jdbcUrl(), "--user", "msika_owner", "--council", l.councilId().toString(),
                "--key-file", SEAL_KEY_FILE.toString()));
        args.addAll(List.of(extraArgs));
        int code = VerifySealsCli.run(args.toArray(String[]::new), env,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("scripts/verify-seal exits 0 and says OK for an untouched ledger")
    void cliPasses() {
        Sealed s = sealedLedger();

        Run run = cli(s.l(), Map.of("MSIKA_VERIFY_DB_PASSWORD", "test_owner"));

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("Checked 1 council(s), 3 seal(s), 4 entries.").contains("OK: every seal verifies");
    }

    @Test
    @DisplayName("scripts/verify-seal exits 1 and lists the problems for a hand-edited database")
    void cliFailsOnAHandEditedDatabase() {
        Sealed s = sealedLedger();
        withTriggerOff("ledger_entry", "ledger_entry_append_only", () -> ownerJdbc()
                .sql("update ledger_entry set amount_ngwee = 999999 where id = :id").param("id", s.day1a().id()).update());

        Run run = cli(s.l(), Map.of("MSIKA_VERIFY_DB_PASSWORD", "test_owner"));

        assertThat(run.exitCode()).as(run.out()).isEqualTo(1);
        assertThat(run.out()).contains("FAILED").contains("altered").contains(s.l().councilId().toString())
                .contains(today().minusDays(2).toString());
    }

    @Test
    @DisplayName("scripts/verify-seal exits 2, not 0, when it cannot run: no password, wrong password, no key")
    void cliErrorsAreNotSilentSuccess() throws Exception {
        Sealed s = sealedLedger();

        Run noPassword = cli(s.l(), Map.of());
        assertThat(noPassword.exitCode()).isEqualTo(2);
        assertThat(noPassword.err()).contains("MSIKA_VERIFY_DB_PASSWORD");

        Run wrongPassword = cli(s.l(), Map.of("MSIKA_VERIFY_DB_PASSWORD", "not the password"));
        assertThat(wrongPassword.exitCode()).isEqualTo(2);

        Path missing = Files.createTempDirectory("no-key").resolve("nothing.properties");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerifySealsCli.run(new String[] {"--url", jdbcUrl(), "--key-file", missing.toString()},
                Map.of("MSIKA_VERIFY_DB_PASSWORD", "test_owner"), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no public key");
    }

    @Test
    @DisplayName("the public key can be given on the command line or in the environment instead of a file")
    void cliAcceptsAKeyDirectly() {
        Sealed s = sealedLedger();
        String base64 = Base64.getEncoder().encodeToString(keys.publicKey().getEncoded());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = VerifySealsCli.run(new String[] {"--url", jdbcUrl(), "--council", s.l().councilId().toString()},
                Map.of("MSIKA_VERIFY_DB_PASSWORD", "test_owner", "MSIKA_SEAL_PUBLIC_KEY", base64),
                new PrintStream(out, true, StandardCharsets.UTF_8), System.err);

        assertThat(code).isZero();
    }

    @Test
    @DisplayName("the verifier uses the owner role: the app role cannot read other councils, which is why")
    void verifierNeedsAnOwnerConnection() {
        Sealed s = sealedLedger();

        Integer asApp = jdbc.sql("select count(*) from daily_seal").query(Integer.class).single();

        assertThat(asApp).as("the application role sees no seals without a council set").isZero();
        assertThat(verify(s.l()).seals()).isEqualTo(3);
        assertThat(OffsetDateTime.now(ZoneOffset.UTC)).isNotNull();
    }
}
