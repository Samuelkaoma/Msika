package zm.msika.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.api.ledger.LedgerService;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.payments.PaymentReference;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.PaymentsUnavailableException;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.payments.StatementLine;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/** The nightly reconciliation (card M-16) through the API, with the payments system's statements scripted. */
class ReconciliationApiTest extends ApiTest {

    /** A payments system that only answers statement requests, from what the test planted. */
    static final class ScriptedStatements implements PaymentsPort {
        @Override
        public String transfer(String key, String from, String to, Money amount, String reference) {
            throw new UnsupportedOperationException("no transfers in this test");
        }

        final Map<String, List<StatementLine>> lines = new ConcurrentHashMap<>();
        volatile boolean down;

        @Override
        public String cashIn(String key, String agent, String customer, Money amount, String reference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PaymentsWallet> findWallet(String walletId) {
            return Optional.empty();
        }

        @Override
        public List<StatementLine> statement(String walletId, LocalDate from, LocalDate to) {
            if (down) {
                throw new PaymentsUnavailableException("scripted outage");
            }
            ZoneId lusaka = ZoneId.of("Africa/Lusaka");
            return lines.getOrDefault(walletId, List.of()).stream()
                    .filter(l -> {
                        LocalDate d = l.at().atZone(lusaka).toLocalDate();
                        return !d.isBefore(from) && !d.isAfter(to);
                    })
                    .toList();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedStatements scriptedStatements() {
            return new ScriptedStatements();
        }
    }

    record DifferenceBody(String kind, String paymentsTransactionId, String entryKind, UUID stallId,
            Long ledgerEntryId, Long msikaAmountNgwee, Long paymentsAmountNgwee) {}

    record ReportBody(UUID councilId, LocalDate businessDate, String ranBy, int paymentsCount, int msikaCount,
            List<DifferenceBody> differences) {}

    @Autowired
    ScriptedStatements payments;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    LedgerService ledger;

    private Fixture council;
    private UUID stallId;
    private String trader;
    private String merchant;
    private String supplier;
    private String tag;
    private Instant at;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        payments.down = false;
        council = newCouncil();
        UUID market = UUID.randomUUID();
        stallId = UUID.randomUUID();
        tag = UUID.randomUUID().toString().substring(0, 8);
        trader = "w-trader-" + tag;
        merchant = "w-merchant-" + tag;
        supplier = "w-supplier-" + tag;
        at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        today = at.atZone(ZoneId.of("Africa/Lusaka")).toLocalDate();
        tenant.inCouncil(council.councilId(), () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'REC', 'Reconciliation Market', '20:00', now())")
                    .param("id", market).param("c", council.councilId()).update();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'REC-A-1', 'STANDARD', 'ALLOCATED', :token)")
                    .param("id", stallId).param("c", council.councilId()).param("m", market)
                    .param("token", UUID.randomUUID().toString().replace("-", "")).update();
            wallet(trader, "TRADER", stallId);
            wallet(merchant, "MERCHANT", stallId);
            wallet(supplier, "SUPPLIER", null);
        });
        payments.lines.put(trader, new ArrayList<>());
        payments.lines.put(merchant, new ArrayList<>());
    }

    private void wallet(String id, String role, UUID stall) {
        jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, stall_id) values (:w, :c, :r, :s)")
                .param("w", id).param("c", council.councilId()).param("r", role).param("s", stall).update();
    }

    private String tx(String name) {
        return tag + "-" + name;
    }

    private void statementLine(String wallet, String name, String kind, long amount, String counterparty, String reference) {
        payments.lines.get(wallet).add(new StatementLine(tx(name), kind, amount, counterparty, reference, at));
    }

    private void record(String name, EntryKind kind, long amount) {
        Provenance provenance = kind == EntryKind.DEPOSIT ? Provenance.DEPOSITED : Provenance.VERIFIED;
        ledger.record(new NewEntry(council.councilId(), stallId, kind, provenance, Money.ofMinor(amount, Currency.ZMW),
                at, "zamsim:" + tx(name), null));
    }

    private String path() {
        return "/api/v1/councils/" + council.councilId() + "/reconciliation/" + today;
    }

    /** One of each difference, plus one payment that matches. */
    private void plantAllKinds() {
        String ours = PaymentReference.of(council.councilId(), UUID.randomUUID());
        statementLine(trader, "match", "CASH_IN", 45_000, "w-agent", ours);
        record("match", EntryKind.DEPOSIT, 45_000);

        statementLine(trader, "lost", "CASH_IN", 30_000, "w-agent", PaymentReference.of(council.councilId(), UUID.randomUUID()));

        record("ghost", EntryKind.MERCHANT_SALE, 2_500);

        statementLine(trader, "supplier", "TRANSFER", -10_000, supplier, null);
        record("supplier", EntryKind.SUPPLIER_PAYMENT, 9_000);
    }

    @Test
    @DisplayName("each planted kind of difference is reported, stored, and readable by an officer")
    void reportsEachKind() {
        plantAllKinds();

        Result<ReportBody> ran = api().post(council.adminToken(), path(), null, ReportBody.class);

        assertThat(ran.status()).isEqualTo(200);
        assertThat(ran.body().paymentsCount()).isEqualTo(3);
        assertThat(ran.body().msikaCount()).isEqualTo(3);
        assertThat(ran.body().differences()).extracting(DifferenceBody::kind, DifferenceBody::paymentsTransactionId,
                        DifferenceBody::msikaAmountNgwee, DifferenceBody::paymentsAmountNgwee)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MISSING_IN_MSIKA", tx("lost"), null, 30_000L),
                        org.assertj.core.groups.Tuple.tuple("MISSING_IN_PAYMENTS", tx("ghost"), 2_500L, null),
                        org.assertj.core.groups.Tuple.tuple("AMOUNT_DIFFERS", tx("supplier"), 9_000L, 10_000L));

        Result<ReportBody> read = api().get(council.officerToken(), path(), ReportBody.class);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().differences()).isEqualTo(ran.body().differences());
        assertThat(read.body().ranBy()).isEqualTo(council.adminId().toString());
    }

    @Test
    @DisplayName("running again replaces the report: a gap filled by a late callback disappears")
    void rerunReplaces() {
        plantAllKinds();
        api().post(council.adminToken(), path(), null, ReportBody.class);

        record("lost", EntryKind.DEPOSIT, 30_000);
        Result<ReportBody> again = api().post(council.adminToken(), path(), null, ReportBody.class);

        assertThat(again.body().differences()).extracting(DifferenceBody::kind)
                .containsExactly("MISSING_IN_PAYMENTS", "AMOUNT_DIFFERS");
        assertThat(api().get(council.adminToken(), path(), ReportBody.class).body().differences()).hasSize(2);
    }

    @Test
    @DisplayName("when the payments system is down nothing is stored and the earlier report stays")
    void paymentsDown() {
        plantAllKinds();
        api().post(council.adminToken(), path(), null, ReportBody.class);

        payments.down = true;
        Result<String> failed = api().post(council.adminToken(), path(), null, String.class);

        assertThat(failed.status()).isEqualTo(502);
        assertThat(api().get(council.adminToken(), path(), ReportBody.class).body().differences()).hasSize(3);
    }

    @Test
    @DisplayName("a day never reconciled is 404; an officer cannot run it; another council cannot read it")
    void access() {
        assertThat(api().get(council.adminToken(), path(), String.class).status()).isEqualTo(404);
        assertThat(api().post(council.officerToken(), path(), null, String.class).status()).isEqualTo(403);

        api().post(council.adminToken(), path(), null, ReportBody.class);
        Fixture other = newCouncil();
        assertThat(api().get(other.adminToken(), path(), String.class).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("the nightly job, run the next morning, reconciles yesterday for the council")
    void nightlyJob(@Autowired ReconciliationService service, @Autowired zm.msika.core.council.CouncilRepository councils) {
        plantAllKinds();
        java.time.Clock nextMorning = java.time.Clock.fixed(at.plus(1, ChronoUnit.DAYS), ZoneId.of("UTC"));
        ReconciliationJob job = new ReconciliationJob(councils, service, nextMorning, 1);

        job.reconcileFor(council.councilId());

        ReportBody report = api().get(council.adminToken(), path(), ReportBody.class).body();
        assertThat(report.ranBy()).isEqualTo(ReconciliationJob.ACTOR);
        assertThat(report.differences()).hasSize(3);
    }
}
