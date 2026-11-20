package zm.msika.api.reconciliation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import zm.msika.api.ledger.LedgerRows;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.payments.PaymentsPort;
import zm.msika.core.payments.StatementLine;
import zm.msika.core.reconciliation.Difference;
import zm.msika.core.reconciliation.Reconciler;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;

/**
 * Runs the reconciliation for one council and day (card M-16) and keeps the report.
 *
 * <p>It works in three steps so that no database transaction stays open while the payments system is called:
 * read the council's wallets, fetch their statements, then compare with the ledger and store the report in one
 * transaction. Running it again for the same day replaces that day's report.
 */
@Service
public class ReconciliationService {

    /** One stored report. */
    public record Report(UUID councilId, LocalDate businessDate, Instant ranAt, String ranBy, int paymentsCount,
            int msikaCount, List<Difference> differences) {}

    private final TenantContext tenant;
    private final CouncilRepository councils;
    private final WalletRegistry wallets;
    private final PaymentsPort payments;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Clock clock;

    ReconciliationService(TenantContext tenant, CouncilRepository councils, WalletRegistry wallets,
            PaymentsPort payments, JdbcClient jdbc, AuditLog audit, Clock clock) {
        this.tenant = tenant;
        this.councils = councils;
        this.wallets = wallets;
        this.payments = payments;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Compares the day and stores the report.
     *
     * @param actor who asked: a user id, or {@code system:reconciliation} for the nightly job
     * @throws zm.msika.core.payments.PaymentsUnavailableException if a statement could not be fetched; nothing is
     *     stored, and the previous report for the day (if any) stays
     */
    public Report reconcile(UUID councilId, LocalDate date, String actor) {
        Council council = councils.find(councilId)
                .orElseThrow(() -> new NotFoundException("no council " + councilId));
        ZoneId zone = council.timezone();
        List<WalletOwner> owned = tenant.inCouncil(councilId, () -> wallets.list(councilId));

        Map<String, List<StatementLine>> statements = new LinkedHashMap<>();
        for (WalletOwner wallet : owned) {
            if (wallet.role() == WalletOwner.Role.TRADER || wallet.role() == WalletOwner.Role.MERCHANT) {
                statements.put(wallet.walletId(), payments.statement(wallet.walletId(), date.minusDays(1), date.plusDays(1)));
            }
        }
        List<Reconciler.Expected> expected = Reconciler.expected(councilId, zone, owned, statements);

        return tenant.inCouncil(councilId, () -> {
            List<LedgerEntry> ofDay = entriesMovedOn(councilId, date, zone);
            List<String> keys = expected.stream()
                    .filter(e -> e.date().equals(date))
                    .map(e -> Reconciler.keyOf(e.transactionId()))
                    .toList();
            Map<String, LedgerEntry> elsewhere = entriesByKey(councilId, keys);
            Reconciler.Result result = Reconciler.compare(date, expected, ofDay, elsewhere);
            Instant now = clock.instant();
            store(councilId, date, now, actor, result);
            if (!result.differences().isEmpty()) {
                audit.record(councilId, actor, "reconciliation.differences",
                        date + ": " + result.differences().size() + " difference(s)");
            }
            return new Report(councilId, date, now, actor, result.paymentsCount(), result.msikaCount(),
                    result.differences());
        });
    }

    /** The stored report for a day, if the reconciliation has run for it. */
    public Optional<Report> report(UUID councilId, LocalDate date) {
        return tenant.inCouncil(councilId, () -> jdbc.sql("select ran_at, ran_by, payments_count, msika_count "
                        + "from reconciliation_run where council_id = :c and business_date = :d")
                .param("c", councilId).param("d", date)
                .query((rs, n) -> new Report(councilId, date,
                        rs.getObject("ran_at", OffsetDateTime.class).toInstant(), rs.getString("ran_by"),
                        rs.getInt("payments_count"), rs.getInt("msika_count"), differences(councilId, date)))
                .optional());
    }

    private List<Difference> differences(UUID councilId, LocalDate date) {
        return jdbc.sql("select kind, payments_transaction_id, entry_kind, stall_id, ledger_entry_id, "
                        + "msika_amount_ngwee, payments_amount_ngwee from reconciliation_difference "
                        + "where council_id = :c and business_date = :d order by array_position(array['MISSING_IN_MSIKA', "
                        + "'MISSING_IN_PAYMENTS', 'AMOUNT_DIFFERS'], kind), payments_transaction_id")
                .param("c", councilId).param("d", date)
                .query((rs, n) -> new Difference(
                        Difference.Kind.valueOf(rs.getString("kind")),
                        rs.getString("payments_transaction_id"),
                        EntryKind.valueOf(rs.getString("entry_kind")),
                        rs.getObject("stall_id", UUID.class),
                        rs.getObject("ledger_entry_id", Long.class),
                        rs.getObject("msika_amount_ngwee", Long.class),
                        rs.getObject("payments_amount_ngwee", Long.class)))
                .list();
    }

    private List<LedgerEntry> entriesMovedOn(UUID councilId, LocalDate date, ZoneId zone) {
        OffsetDateTime start = date.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        return jdbc.sql("select " + LedgerRows.COLUMNS + " from ledger_entry "
                        + "where council_id = :c and occurred_at >= :start and occurred_at < :end "
                        + "and kind in ('DEPOSIT', 'MERCHANT_SALE', 'SUPPLIER_PAYMENT') order by id")
                .param("c", councilId).param("start", start).param("end", end)
                .query((rs, n) -> LedgerRows.map(rs))
                .list();
    }

    private Map<String, LedgerEntry> entriesByKey(UUID councilId, List<String> keys) {
        Map<String, LedgerEntry> found = new HashMap<>();
        if (keys.isEmpty()) {
            return found;
        }
        for (int from = 0; from < keys.size(); from += 1000) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + 1000));
            jdbc.sql("select " + LedgerRows.COLUMNS + " from ledger_entry "
                            + "where council_id = :c and idempotency_key in (:keys)")
                    .param("c", councilId).param("keys", chunk)
                    .query((rs, n) -> LedgerRows.map(rs))
                    .list()
                    .forEach(e -> found.put(e.idempotencyKey(), e));
        }
        return found;
    }

    private void store(UUID councilId, LocalDate date, Instant now, String actor, Reconciler.Result result) {
        jdbc.sql("delete from reconciliation_difference where council_id = :c and business_date = :d")
                .param("c", councilId).param("d", date).update();
        jdbc.sql("insert into reconciliation_run (council_id, business_date, ran_at, ran_by, payments_count, "
                        + "msika_count, difference_count) values (:c, :d, :at, :by, :p, :m, :n) "
                        + "on conflict (council_id, business_date) do update set ran_at = excluded.ran_at, "
                        + "ran_by = excluded.ran_by, payments_count = excluded.payments_count, "
                        + "msika_count = excluded.msika_count, difference_count = excluded.difference_count")
                .param("c", councilId).param("d", date)
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("by", actor)
                .param("p", result.paymentsCount()).param("m", result.msikaCount())
                .param("n", result.differences().size())
                .update();
        for (Difference d : result.differences()) {
            jdbc.sql("insert into reconciliation_difference (council_id, business_date, kind, payments_transaction_id, "
                            + "entry_kind, stall_id, ledger_entry_id, msika_amount_ngwee, payments_amount_ngwee) "
                            + "values (:c, :d, :kind, :tx, :entryKind, :stall, :entry, :msika, :payments)")
                    .param("c", councilId).param("d", date).param("kind", d.kind().name())
                    .param("tx", d.paymentsTransactionId()).param("entryKind", d.entryKind().name())
                    .param("stall", d.stallId()).param("entry", d.ledgerEntryId())
                    .param("msika", d.msikaAmountNgwee()).param("payments", d.paymentsAmountNgwee())
                    .update();
        }
    }
}
