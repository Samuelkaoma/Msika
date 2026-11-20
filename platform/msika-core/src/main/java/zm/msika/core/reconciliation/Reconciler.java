package zm.msika.core.reconciliation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import zm.msika.core.payments.HandlePaymentEvent;
import zm.msika.core.payments.PaymentReference;
import zm.msika.core.payments.StatementLine;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;

/**
 * Compares one council's day on Msika's ledger with the same day in the payments system (card M-16). Pure: it is
 * handed both sides and returns the differences, so every case can be tested without a database or a network.
 *
 * <p><strong>What the payments system says Msika should have.</strong> The statements of the council's own TRADER
 * and MERCHANT wallets are read with the same rules Msika uses when a callback arrives:
 *
 * <ul>
 *   <li>a CASH_IN into a TRADER wallet carrying one of this council's payment references is a DEPOSIT;
 *   <li>a TRANSFER or REQUEST_TO_PAY into a MERCHANT wallet is a MERCHANT_SALE;
 *   <li>a TRANSFER or REQUEST_TO_PAY out of a TRADER or MERCHANT wallet to one of this council's SUPPLIER wallets
 *       is a SUPPLIER_PAYMENT.
 * </ul>
 *
 * Anything else on those wallets (a cash-in by another system, a withdrawal, a payment to a friend) is not
 * Msika's business and is ignored, exactly as the callback handler ignores it.
 *
 * <p><strong>The day.</strong> Both sides are compared by the date the money moved in the council's time zone,
 * not by Msika's business date, because a late callback for a sealed day is recorded on the next open business
 * date. Payments near midnight can be stamped a little differently on each side, so the payments statements are
 * read for the day before and the day after as well: a payment is only "missing" if it is missing from all three
 * days, and only "missing in Msika" if Msika has it on no day at all.
 *
 * <p>Reversals are not compared: they are Msika's own corrections and have no payments transaction.
 */
public final class Reconciler {

    private static final Set<String> TRADE_KINDS = Set.of("TRANSFER", "REQUEST_TO_PAY");
    private static final Set<EntryKind> PAYMENT_KINDS =
            Set.of(EntryKind.DEPOSIT, EntryKind.MERCHANT_SALE, EntryKind.SUPPLIER_PAYMENT);
    private static final String KEY_PREFIX = HandlePaymentEvent.ledgerKey("");

    private Reconciler() {}

    /** A movement the payments system made that Msika should have on its ledger. */
    public record Expected(String transactionId, EntryKind kind, UUID stallId, long amountNgwee, LocalDate date) {}

    /** The outcome for one day. */
    public record Result(int paymentsCount, int msikaCount, List<Difference> differences) {}

    /**
     * Turns wallet statements into the payments Msika should have recorded, one per transaction.
     *
     * @param wallets every wallet registered to the council
     * @param statements the statement lines of the council's TRADER and MERCHANT wallets, by wallet id
     */
    public static List<Expected> expected(UUID councilId, ZoneId zone, Collection<WalletOwner> wallets,
            Map<String, List<StatementLine>> statements) {
        Map<String, WalletOwner> byId = new HashMap<>();
        for (WalletOwner w : wallets) {
            if (w.councilId().equals(councilId)) {
                byId.put(w.walletId(), w);
            }
        }
        Map<String, Expected> found = new LinkedHashMap<>();
        for (Map.Entry<String, List<StatementLine>> e : statements.entrySet()) {
            WalletOwner wallet = byId.get(e.getKey());
            if (wallet == null) {
                continue;
            }
            for (StatementLine line : e.getValue()) {
                classify(councilId, wallet, line, byId).ifPresent(kind -> found.putIfAbsent(line.transactionId(),
                        new Expected(line.transactionId(), kind, wallet.stallId(), Math.abs(line.amountNgwee()),
                                line.at().atZone(zone).toLocalDate())));
            }
        }
        return List.copyOf(found.values());
    }

    private static Optional<EntryKind> classify(UUID councilId, WalletOwner wallet, StatementLine line,
            Map<String, WalletOwner> byId) {
        boolean in = line.amountNgwee() > 0;
        if (wallet.role() == WalletOwner.Role.TRADER && in && "CASH_IN".equals(line.kind())) {
            return PaymentReference.parse(line.reference())
                    .filter(p -> p.councilId().equals(councilId))
                    .map(p -> EntryKind.DEPOSIT);
        }
        if (!TRADE_KINDS.contains(line.kind())) {
            return Optional.empty();
        }
        if (wallet.role() == WalletOwner.Role.MERCHANT && in) {
            return Optional.of(EntryKind.MERCHANT_SALE);
        }
        if (!in && line.counterpartyWalletId() != null) {
            WalletOwner to = byId.get(line.counterpartyWalletId());
            if (to != null && to.role() == WalletOwner.Role.SUPPLIER) {
                return Optional.of(EntryKind.SUPPLIER_PAYMENT);
            }
        }
        return Optional.empty();
    }

    /** The ledger key Msika records a payments transaction under. */
    public static String keyOf(String transactionId) {
        return HandlePaymentEvent.ledgerKey(transactionId);
    }

    /** True for a ledger entry that records a payments transaction (and so must appear in the payments system). */
    public static boolean isPaymentEntry(LedgerEntry entry) {
        return PAYMENT_KINDS.contains(entry.kind()) && entry.idempotencyKey().startsWith(KEY_PREFIX);
    }

    /**
     * Compares the day.
     *
     * @param date the day being reconciled, in the council's time zone
     * @param expected what the payments system moved, for the day and the days either side of it
     * @param msikaOfDay Msika's ledger entries whose money moved on {@code date}
     * @param msikaElsewhere Msika's entries, on any day, for the expected transactions of {@code date}, by key
     */
    public static Result compare(LocalDate date, List<Expected> expected, List<LedgerEntry> msikaOfDay,
            Map<String, LedgerEntry> msikaElsewhere) {
        Objects.requireNonNull(date);
        Map<String, Expected> byTransaction = new HashMap<>();
        for (Expected e : expected) {
            byTransaction.put(e.transactionId(), e);
        }
        List<LedgerEntry> ours = msikaOfDay.stream().filter(Reconciler::isPaymentEntry).toList();
        Map<String, LedgerEntry> oursByKey = new HashMap<>();
        for (LedgerEntry entry : ours) {
            oursByKey.put(entry.idempotencyKey(), entry);
        }

        List<Difference> differences = new ArrayList<>();
        int paymentsCount = 0;
        for (Expected e : expected) {
            if (!e.date().equals(date)) {
                continue;
            }
            paymentsCount++;
            String key = keyOf(e.transactionId());
            LedgerEntry entry = oursByKey.containsKey(key) ? oursByKey.get(key) : msikaElsewhere.get(key);
            if (entry == null) {
                differences.add(new Difference(Difference.Kind.MISSING_IN_MSIKA, e.transactionId(), e.kind(),
                        e.stallId(), null, null, e.amountNgwee()));
            } else if (entry.amount().minorUnits() != e.amountNgwee()) {
                differences.add(new Difference(Difference.Kind.AMOUNT_DIFFERS, e.transactionId(), e.kind(),
                        entry.stallId(), entry.id(), entry.amount().minorUnits(), e.amountNgwee()));
            }
        }
        for (LedgerEntry entry : ours) {
            String transactionId = entry.idempotencyKey().substring(KEY_PREFIX.length());
            Expected e = byTransaction.get(transactionId);
            if (e == null) {
                differences.add(new Difference(Difference.Kind.MISSING_IN_PAYMENTS, transactionId, entry.kind(),
                        entry.stallId(), entry.id(), entry.amount().minorUnits(), null));
            } else if (!e.date().equals(date) && entry.amount().minorUnits() != e.amountNgwee()) {
                // Stamped on a neighbouring day by the payments system; the loop above did not see it.
                differences.add(new Difference(Difference.Kind.AMOUNT_DIFFERS, transactionId, entry.kind(),
                        entry.stallId(), entry.id(), entry.amount().minorUnits(), e.amountNgwee()));
            }
        }
        differences.sort(Comparator.comparing(Difference::kind).thenComparing(Difference::paymentsTransactionId));
        return new Result(paymentsCount, ours.size(), List.copyOf(differences));
    }
}
