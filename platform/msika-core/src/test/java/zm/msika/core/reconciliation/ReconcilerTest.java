package zm.msika.core.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.payments.PaymentReference;
import zm.msika.core.payments.StatementLine;
import zm.msika.core.reconciliation.Difference.Kind;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletOwner.Role;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/** The nightly reconciliation's comparison (card M-16), with each kind of difference planted on purpose. */
class ReconcilerTest {

    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    /** 17:30 in Lusaka on DAY. */
    private static final Instant AT = Instant.parse("2026-11-24T15:30:00Z");

    private final UUID council = UUID.randomUUID();
    private final UUID stall = UUID.randomUUID();
    private final List<WalletOwner> wallets = new ArrayList<>();
    private final Map<String, List<StatementLine>> statements = new HashMap<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        wallets.add(new WalletOwner("w-trader", council, Role.TRADER, stall, null));
        wallets.add(new WalletOwner("w-merchant", council, Role.MERCHANT, stall, null));
        wallets.add(new WalletOwner("w-supplier", council, Role.SUPPLIER, null, null));
        wallets.add(new WalletOwner("w-agent", council, Role.AGENT, null, UUID.randomUUID()));
        statements.put("w-trader", new ArrayList<>());
        statements.put("w-merchant", new ArrayList<>());
    }

    private String ours() {
        return PaymentReference.of(council, UUID.randomUUID());
    }

    private void line(String wallet, String tx, String kind, long amount, String counterparty, String reference, Instant at) {
        statements.get(wallet).add(new StatementLine(tx, kind, amount, counterparty, reference, at));
    }

    private LedgerEntry entry(String tx, EntryKind kind, long amount, Instant at) {
        Provenance provenance = kind == EntryKind.DEPOSIT ? Provenance.DEPOSITED : Provenance.VERIFIED;
        LedgerEntry e = new LedgerEntry(nextId++, council, stall, kind, provenance, Money.ofMinor(amount, Currency.ZMW),
                at.atZone(LUSAKA).toLocalDate(), at, Reconciler.keyOf(tx), null);
        ledger.add(e);
        return e;
    }

    private Reconciler.Result run() {
        List<Reconciler.Expected> expected = Reconciler.expected(council, LUSAKA, wallets, statements);
        Map<String, LedgerEntry> byKey = new HashMap<>();
        ledger.forEach(e -> byKey.put(e.idempotencyKey(), e));
        List<LedgerEntry> ofDay = ledger.stream().filter(e -> e.occurredAt().atZone(LUSAKA).toLocalDate().equals(DAY)).toList();
        return Reconciler.compare(DAY, expected, ofDay, byKey);
    }

    @Test
    @DisplayName("a day where both sides agree has no differences")
    void agreement() {
        line("w-trader", "tx-dep", "CASH_IN", 45_000, "w-agent", ours(), AT);
        line("w-merchant", "tx-sale", "REQUEST_TO_PAY", 2_500, "w-customer", null, AT);
        line("w-trader", "tx-sup", "TRANSFER", -10_000, "w-supplier", null, AT);
        entry("tx-dep", EntryKind.DEPOSIT, 45_000, AT);
        entry("tx-sale", EntryKind.MERCHANT_SALE, 2_500, AT);
        entry("tx-sup", EntryKind.SUPPLIER_PAYMENT, 10_000, AT);

        Reconciler.Result result = run();

        assertThat(result.differences()).isEmpty();
        assertThat(result.paymentsCount()).isEqualTo(3);
        assertThat(result.msikaCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a deposit settled in the payments system but not on Msika's ledger is MISSING_IN_MSIKA")
    void missingInMsika() {
        line("w-trader", "tx-lost", "CASH_IN", 45_000, "w-agent", ours(), AT);

        assertThat(run().differences()).containsExactly(
                new Difference(Kind.MISSING_IN_MSIKA, "tx-lost", EntryKind.DEPOSIT, stall, null, null, 45_000L));
    }

    @Test
    @DisplayName("an entry on Msika's ledger with no settled payment is MISSING_IN_PAYMENTS")
    void missingInPayments() {
        LedgerEntry e = entry("tx-ghost", EntryKind.MERCHANT_SALE, 2_500, AT);

        assertThat(run().differences()).containsExactly(
                new Difference(Kind.MISSING_IN_PAYMENTS, "tx-ghost", EntryKind.MERCHANT_SALE, stall, e.id(), 2_500L, null));
    }

    @Test
    @DisplayName("the same payment with different amounts is AMOUNT_DIFFERS")
    void amountDiffers() {
        line("w-trader", "tx-sup", "TRANSFER", -10_000, "w-supplier", null, AT);
        LedgerEntry e = entry("tx-sup", EntryKind.SUPPLIER_PAYMENT, 9_000, AT);

        assertThat(run().differences()).containsExactly(
                new Difference(Kind.AMOUNT_DIFFERS, "tx-sup", EntryKind.SUPPLIER_PAYMENT, stall, e.id(), 9_000L, 10_000L));
    }

    @Test
    @DisplayName("all three kinds planted in one day are all reported, in a fixed order")
    void allThree() {
        line("w-trader", "tx-a-lost", "CASH_IN", 1_000, "w-agent", ours(), AT);
        entry("tx-b-ghost", EntryKind.DEPOSIT, 2_000, AT);
        line("w-merchant", "tx-c-sale", "TRANSFER", 3_000, "w-customer", null, AT);
        entry("tx-c-sale", EntryKind.MERCHANT_SALE, 3_100, AT);

        assertThat(run().differences()).extracting(Difference::kind, Difference::paymentsTransactionId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Kind.MISSING_IN_MSIKA, "tx-a-lost"),
                        org.assertj.core.groups.Tuple.tuple(Kind.MISSING_IN_PAYMENTS, "tx-b-ghost"),
                        org.assertj.core.groups.Tuple.tuple(Kind.AMOUNT_DIFFERS, "tx-c-sale"));
    }

    @Test
    @DisplayName("money that is not Msika's business is ignored: another system's cash-in, a payment to a friend, a withdrawal")
    void notOurBusiness() {
        line("w-trader", "tx-other-cashin", "CASH_IN", 5_000, "w-agent", "someone-else:123", AT);
        line("w-trader", "tx-other-council", "CASH_IN", 5_000, "w-agent", PaymentReference.of(UUID.randomUUID(), UUID.randomUUID()), AT);
        line("w-trader", "tx-friend", "TRANSFER", -5_000, "w-friend", null, AT);
        line("w-trader", "tx-incoming", "TRANSFER", 5_000, "w-friend", null, AT);
        line("w-merchant", "tx-withdraw", "CASH_OUT", -5_000, null, null, AT);

        Reconciler.Result result = run();

        assertThat(result.differences()).isEmpty();
        assertThat(result.paymentsCount()).isZero();
    }

    @Test
    @DisplayName("a late callback recorded on a later business date is still matched, by when the money moved")
    void lateCallbackOnLaterBusinessDate() {
        line("w-trader", "tx-late", "CASH_IN", 45_000, "w-agent", ours(), AT);
        Instant movedAt = AT;
        LedgerEntry late = new LedgerEntry(nextId++, council, stall, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                Money.ofMinor(45_000, Currency.ZMW), DAY.plusDays(1), movedAt, Reconciler.keyOf("tx-late"), null);
        ledger.add(late);

        assertThat(run().differences()).isEmpty();
    }

    @Test
    @DisplayName("a payment stamped just across midnight on one side is not reported as missing")
    void acrossMidnight() {
        // The payments system says 00:00:01 the next day; Msika has 23:59:59 on DAY.
        Instant justBefore = Instant.parse("2026-11-24T21:59:59Z");
        Instant justAfter = Instant.parse("2026-11-24T22:00:01Z");
        line("w-trader", "tx-midnight", "CASH_IN", 45_000, "w-agent", ours(), justAfter);
        entry("tx-midnight", EntryKind.DEPOSIT, 45_000, justBefore);

        assertThat(run().differences()).isEmpty();
    }

    @Test
    @DisplayName("an amount difference is still found when the two sides stamp the payment on neighbouring days")
    void amountDiffersAcrossMidnight() {
        Instant justBefore = Instant.parse("2026-11-24T21:59:59Z");
        Instant justAfter = Instant.parse("2026-11-24T22:00:01Z");
        line("w-trader", "tx-midnight", "CASH_IN", 45_000, "w-agent", ours(), justAfter);
        LedgerEntry e = entry("tx-midnight", EntryKind.DEPOSIT, 40_000, justBefore);

        assertThat(run().differences()).containsExactly(
                new Difference(Kind.AMOUNT_DIFFERS, "tx-midnight", EntryKind.DEPOSIT, stall, e.id(), 40_000L, 45_000L));
    }

    @Test
    @DisplayName("reversals and declared sales are Msika's own and are never compared")
    void ownEntriesIgnored() {
        LedgerEntry dep = entry("tx-dep", EntryKind.DEPOSIT, 45_000, AT);
        line("w-trader", "tx-dep", "CASH_IN", 45_000, "w-agent", ours(), AT);
        ledger.add(new LedgerEntry(nextId++, council, stall, EntryKind.REVERSAL, Provenance.DEPOSITED,
                Money.ofMinor(-45_000, Currency.ZMW), DAY, AT, "dispute:1", dep.id()));
        ledger.add(new LedgerEntry(nextId++, council, stall, EntryKind.DECLARED_SALE, Provenance.DECLARED,
                Money.ofMinor(9_000, Currency.ZMW), DAY, AT, "sms:abc", null));

        Reconciler.Result result = run();

        assertThat(result.differences()).isEmpty();
        assertThat(result.msikaCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment between two of the council's own wallets is counted once")
    void countedOnce() {
        // The trader pays their own merchant wallet: it appears on both statements.
        line("w-trader", "tx-self", "TRANSFER", -1_000, "w-merchant", null, AT);
        line("w-merchant", "tx-self", "TRANSFER", 1_000, "w-trader", null, AT);
        entry("tx-self", EntryKind.MERCHANT_SALE, 1_000, AT);

        Reconciler.Result result = run();

        assertThat(result.differences()).isEmpty();
        assertThat(result.paymentsCount()).isEqualTo(1);
    }
}
