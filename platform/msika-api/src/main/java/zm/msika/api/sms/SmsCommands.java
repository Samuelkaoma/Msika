package zm.msika.api.sms;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zm.msika.api.ledger.LedgerService;
import zm.msika.api.payments.PaymentService;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.deposit.ChangeDeposit;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.deposit.DepositReference;
import zm.msika.core.deposit.DepositRepository;
import zm.msika.core.deposit.DepositStatus;
import zm.msika.core.deposit.RequestDeposit;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.payments.PaymentsUnavailableException;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.sms.SmsCommand;
import zm.msika.core.sms.SmsFormat;
import zm.msika.core.sms.SmsRouting.Sender;
import zm.msika.core.sms.SmsTemplate;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/**
 * Every command in {@code docs/SMS-PROTOCOL.md}, wired to its use case (card M-19). It runs inside the transaction
 * that processes the text, as the sender's council.
 *
 * <p>A use case that refuses (an inactive agent, a stall with no wallet) is run inside a savepoint: what it wrote is
 * rolled back, the text is still marked processed, and the sender is told why in a {@code NOT_DONE} reply. Calls to
 * the payments system are left for after the commit (see {@link SmsCommandHandler.Later}).
 *
 * <p>Commands whose figures come from later cards answer honestly that they have none yet: levy (M-24, M-25),
 * statements (M-31, M-32) and loans (M-38).
 */
@Component
class SmsCommands implements SmsCommandHandler {

    /** How long an agent may take back their own deposit. */
    static final Duration UNDO_WINDOW = Duration.ofMinutes(10);

    private static final String ACTOR = "sms";

    private final SmsQueries queries;
    private final TenantContext tenant;
    private final CouncilRepository councils;
    private final LedgerService ledger;
    private final RequestDeposit requestDeposit;
    private final ChangeDeposit changeDeposit;
    private final DepositRepository deposits;
    private final WalletRegistry wallets;
    private final PaymentService payments;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate savepoint;
    private final SecureRandom random = new SecureRandom();

    SmsCommands(SmsQueries queries, TenantContext tenant, CouncilRepository councils, LedgerService ledger,
            RequestDeposit requestDeposit, ChangeDeposit changeDeposit, DepositRepository deposits, WalletRegistry wallets,
            PaymentService payments, AuditLog audit, Clock clock, PlatformTransactionManager transactions) {
        this.queries = queries;
        this.tenant = tenant;
        this.councils = councils;
        this.ledger = ledger;
        this.requestDeposit = requestDeposit;
        this.changeDeposit = changeDeposit;
        this.deposits = deposits;
        this.wallets = wallets;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public Result handle(UUID inboundId, Sender sender, String from, SmsCommand command, Instant receivedAt) {
        try {
            return switch (command) {
                case SmsCommand.Register r -> register(sender, from, r, receivedAt);
                case SmsCommand.Consent c -> consent(sender, from);
                case SmsCommand.DeclareSale s -> sale(inboundId, sender, from, s, receivedAt);
                case SmsCommand.Today t -> today(sender, from);
                case SmsCommand.Balance b -> balance(sender, from);
                case SmsCommand.Levy l -> Result.reply("LEVY", Reply.of(from, SmsTemplate.LEVY_UNKNOWN));
                case SmsCommand.Month m -> Result.reply("MONTH", Reply.of(from, SmsTemplate.NO_STATEMENT));
                case SmsCommand.StatementOk o -> Result.reply("OK", Reply.of(from, SmsTemplate.NO_STATEMENT));
                case SmsCommand.StatementWrong w -> Result.reply("WRONG", Reply.of(from, SmsTemplate.NO_STATEMENT));
                case SmsCommand.DisputeDeposit d -> dispute(sender, from, d);
                case SmsCommand.Loan l -> Result.reply("LOAN", Reply.of(from, SmsTemplate.LOAN_NONE));
                case SmsCommand.Help h -> Result.reply("HELP", Reply.of(from,
                        sender != null && sender.isTrader() ? SmsTemplate.HELP_TRADER : SmsTemplate.HELP_AGENT));
                case SmsCommand.Stop s -> summaries(sender, from, false);
                case SmsCommand.Start s -> summaries(sender, from, true);
                case SmsCommand.Deposit d -> deposit(inboundId, sender, from, d);
                case SmsCommand.Undo u -> undo(sender, from, u, receivedAt);
                case SmsCommand.Round r -> round(sender, from);
            };
        } catch (Refused e) {
            return Result.reply("REFUSED", Reply.of(from, SmsTemplate.NOT_DONE, "REASON", e.getMessage()));
        }
    }

    /** A use case said no; the sender is told why. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    /** Runs a use case that writes, inside a savepoint, turning its refusal into {@link Refused}. */
    private <T> T attempt(Supplier<T> work) {
        try {
            return savepoint.execute(status -> work.get());
        } catch (InvalidInputException | NotFoundException | ConflictException e) {
            throw new Refused(firstSentence(e.getMessage()));
        }
    }

    private static String firstSentence(String message) {
        if (message == null || message.isBlank()) {
            return "it was refused";
        }
        int stop = message.indexOf(". ");
        return stop > 0 ? message.substring(0, stop) : message.replaceAll("\\.$", "");
    }

    private LocalDate today(UUID councilId) {
        return clock.instant().atZone(councils.find(councilId).orElseThrow().timezone()).toLocalDate();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Registration and consent
    // ---------------------------------------------------------------------------------------------------------

    private Result register(Sender sender, String from, SmsCommand.Register r, Instant at) {
        UUID councilId;
        UUID stallId;
        if (sender != null) {
            councilId = sender.councilId();
            List<SmsQueries.StallRef> found = queries.stallsMatching(councilId, r.stallCode()).stream()
                    .filter(s -> s.code().equals(r.stallCode())).toList();
            if (found.isEmpty()) {
                return Result.reply("STALL_NOT_FOUND", Reply.of(from, SmsTemplate.STALL_NOT_FOUND, "STALL", r.stallCode()));
            }
            stallId = found.get(0).id();
        } else {
            List<UUID> councilIds = new ArrayList<>();
            List<SmsQueries.StallRef> found = queries.findStallAnywhere(r.stallCode(), councilIds::add);
            if (found.size() != 1) {
                return Result.reply("STALL_NOT_FOUND", Reply.of(from, SmsTemplate.STALL_NOT_FOUND, "STALL", r.stallCode()));
            }
            councilId = councilIds.get(0);
            stallId = found.get(0).id();
            // The text belongs to no council, but the request belongs to the stall's.
            tenant.use(councilId);
        }
        queries.raiseRegistration(councilId, stallId, from, at);
        audit.record(councilId, ACTOR, "registration.request", "stall " + r.stallCode());
        return Result.reply("REG", Reply.of(from, SmsTemplate.REG_SENT, "STALL", r.stallCode()));
    }

    private Result consent(Sender sender, String from) {
        if (!queries.recordConsent(sender.councilId(), sender.traderId(), clock.instant())) {
            return Result.reply("YES", Reply.of(from, SmsTemplate.NOTHING_TO_CONSENT));
        }
        audit.record(sender.councilId(), ACTOR, "trader.consent", sender.traderId().toString());
        List<SmsQueries.StallRef> stalls = queries.stallsOf(sender.councilId(), sender.traderId());
        if (stalls.isEmpty()) {
            return Result.reply("YES", Reply.of(from, SmsTemplate.NO_STALL));
        }
        return Result.reply("YES", Reply.of(from, SmsTemplate.CONSENT_DONE, "STALL", stalls.get(0).code()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Trader commands
    // ---------------------------------------------------------------------------------------------------------

    /** The trader's stall: the first they hold. A trader with several stalls is answered for the first. */
    private Optional<SmsQueries.StallRef> stallOf(Sender sender) {
        return queries.stallsOf(sender.councilId(), sender.traderId()).stream().findFirst();
    }

    private Result sale(UUID inboundId, Sender sender, String from, SmsCommand.DeclareSale s, Instant receivedAt) {
        Optional<SmsQueries.StallRef> stall = stallOf(sender);
        if (stall.isEmpty()) {
            return Result.reply("NO_STALL", Reply.of(from, SmsTemplate.NO_STALL));
        }
        UUID council = sender.councilId();
        RecordEntry.Recorded recorded = attempt(() -> ledger.record(new NewEntry(council, stall.get().id(),
                EntryKind.DECLARED_SALE, Provenance.DECLARED, Money.ofMinor(s.amountNgwee(), Currency.ZMW), receivedAt,
                "sms:" + inboundId, null)));
        long entryId = recorded.entry().id();
        LocalDate date = recorded.entry().businessDate();
        String reference = queries.saleReference(council, entryId).orElseGet(() -> {
            for (int i = 0; i < 50; i++) {
                String candidate = DepositReference.generate(random);
                if (queries.addSaleReference(council, entryId, date, candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("no free reference for a declared sale");
        });
        long declared = queries.dayTotals(council, stall.get().id(), date).getOrDefault(EntryKind.DECLARED_SALE, 0L);
        return Result.reply("SALE", Reply.of(from, SmsTemplate.SALE_NOTED, "AMOUNT", SmsFormat.kwacha(s.amountNgwee()),
                "AMOUNT2", SmsFormat.kwacha(declared), "REF", reference));
    }

    private Result today(Sender sender, String from) {
        Optional<SmsQueries.StallRef> stall = stallOf(sender);
        if (stall.isEmpty()) {
            return Result.reply("NO_STALL", Reply.of(from, SmsTemplate.NO_STALL));
        }
        Map<EntryKind, Long> t = queries.dayTotals(sender.councilId(), stall.get().id(), today(sender.councilId()));
        return Result.reply("TODAY", Reply.of(from, SmsTemplate.TODAY, "STALL", stall.get().code(),
                "AMOUNT", SmsFormat.kwacha(t.getOrDefault(EntryKind.DEPOSIT, 0L)),
                "AMOUNT2", SmsFormat.kwacha(t.getOrDefault(EntryKind.MERCHANT_SALE, 0L)),
                "AMOUNT3", SmsFormat.kwacha(t.getOrDefault(EntryKind.DECLARED_SALE, 0L)),
                "AMOUNT4", SmsFormat.kwacha(t.getOrDefault(EntryKind.LEVY_PAYMENT, 0L))));
    }

    private Result balance(Sender sender, String from) {
        Optional<SmsQueries.StallRef> stall = stallOf(sender);
        if (stall.isEmpty()) {
            return Result.reply("NO_STALL", Reply.of(from, SmsTemplate.NO_STALL));
        }
        Optional<WalletOwner> wallet = wallets.ofStall(sender.councilId(), stall.get().id(), WalletOwner.Role.TRADER);
        if (wallet.isEmpty()) {
            return Result.reply("NO_WALLET", Reply.of(from, SmsTemplate.NOT_DONE, "REASON", "no wallet is registered for you"));
        }
        String walletId = wallet.get().walletId();
        return new Result("BAL", List.of(), List.of(() -> {
            Long balance;
            try {
                balance = payments.findWallet(walletId).map(PaymentsWallet::balanceNgwee).orElse(null);
            } catch (PaymentsUnavailableException e) {
                balance = null;
            }
            return List.of(balance == null
                    ? Reply.of(from, SmsTemplate.NOT_DONE, "REASON", "the payments system is not answering")
                    : Reply.of(from, SmsTemplate.BALANCE_WALLET, "AMOUNT", SmsFormat.kwacha(balance, true)));
        }));
    }

    private Result dispute(Sender sender, String from, SmsCommand.DisputeDeposit d) {
        List<UUID> mine = queries.stallsOf(sender.councilId(), sender.traderId()).stream().map(SmsQueries.StallRef::id).toList();
        Optional<Deposit> found = deposits.findByReference(sender.councilId(), d.reference(),
                today(sender.councilId()).minusDays(7)).stream().filter(x -> mine.contains(x.stallId())).findFirst();
        if (found.isEmpty() || found.get().status() == DepositStatus.FAILED || found.get().status() == DepositStatus.CANCELLED) {
            return Result.reply("REF_NOT_FOUND", Reply.of(from, SmsTemplate.REF_NOT_FOUND, "REF", d.reference()));
        }
        Deposit deposit = found.get();
        if (deposit.status() == DepositStatus.PENDING) {
            return Result.reply("NOT_CONFIRMED", Reply.of(from, SmsTemplate.DISPUTE_NOT_CONFIRMED, "REF", d.reference()));
        }
        if (deposit.status() == DepositStatus.CONFIRMED) {
            attempt(() -> changeDeposit.dispute(sender.councilId(), deposit.id(), "DISPUTED_BY_TRADER"));
            audit.record(sender.councilId(), ACTOR, "deposit.dispute", deposit.id().toString());
        }
        return Result.reply("NO", Reply.of(from, SmsTemplate.DISPUTE_FLAGGED, "REF", d.reference()));
    }

    private Result summaries(Sender sender, String from, boolean on) {
        queries.setDailySummaries(sender.councilId(), sender.traderId(), on);
        return Result.reply(on ? "START" : "STOP", Reply.of(from, on ? SmsTemplate.STARTED : SmsTemplate.STOPPED));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Agent commands
    // ---------------------------------------------------------------------------------------------------------

    private Result deposit(UUID inboundId, Sender sender, String from, SmsCommand.Deposit d) {
        List<SmsQueries.StallRef> stalls = queries.stallsMatching(sender.councilId(), d.stallCode());
        if (stalls.isEmpty()) {
            return Result.reply("STALL_NOT_FOUND", Reply.of(from, SmsTemplate.STALL_NOT_FOUND, "STALL", d.stallCode()));
        }
        if (stalls.size() > 1) {
            return Result.reply("STALL_AMBIGUOUS", Reply.of(from, SmsTemplate.STALL_AMBIGUOUS, "STALL", d.stallCode()));
        }
        SmsQueries.StallRef stall = stalls.get(0);
        RequestDeposit.Prepared prepared = attempt(() -> requestDeposit.execute(sender.councilId(), sender.agentId(),
                stall.id(), d.amountNgwee(), "sms:" + inboundId));
        Deposit deposit = prepared.deposit();
        audit.record(sender.councilId(), ACTOR, "deposit.start", deposit.id().toString());
        boolean notSentYet = !prepared.request().isFinal() && prepared.request().paymentsTransactionId() == null;
        String amount = SmsFormat.kwacha(d.amountNgwee());
        if (!notSentYet || prepared.agentWalletId() == null || prepared.traderWalletId() == null) {
            return Result.reply("DEP", Reply.of(from, SmsTemplate.DEPOSIT_SENT, "AMOUNT", amount, "STALL", stall.code(),
                    "REF", deposit.reference()));
        }
        return new Result("DEP", List.of(), List.of(() -> {
            try {
                payments.send(prepared.request(), prepared.agentWalletId(), prepared.traderWalletId());
            } catch (PaymentsUnavailableException e) {
                return List.of(Reply.of(from, SmsTemplate.DEPOSIT_UNAVAILABLE, "STALL", stall.code()));
            }
            return List.of(Reply.of(from, SmsTemplate.DEPOSIT_SENT, "AMOUNT", amount, "STALL", stall.code(),
                    "REF", deposit.reference()));
        }));
    }

    private Result undo(Sender sender, String from, SmsCommand.Undo u, Instant receivedAt) {
        Optional<Deposit> found = deposits.findByReference(sender.councilId(), u.reference(),
                today(sender.councilId()).minusDays(1)).stream().filter(x -> x.agentId().equals(sender.agentId())).findFirst();
        if (found.isEmpty()) {
            return Result.reply("REF_NOT_FOUND", Reply.of(from, SmsTemplate.REF_NOT_FOUND, "REF", u.reference()));
        }
        Deposit deposit = found.get();
        String why = null;
        if (deposit.status() != DepositStatus.PENDING) {
            why = "it is already " + deposit.status().name().toLowerCase(java.util.Locale.ROOT);
        } else if (deposit.createdAt().plus(UNDO_WINDOW).isBefore(receivedAt)) {
            why = "more than 10 minutes have passed";
        }
        if (why != null) {
            return Result.reply("UNDO_REFUSED", Reply.of(from, SmsTemplate.UNDO_REFUSED, "REF", u.reference(), "REASON", why));
        }
        attempt(() -> changeDeposit.cancel(sender.councilId(), deposit.id()));
        audit.record(sender.councilId(), ACTOR, "deposit.cancel", deposit.id().toString());
        return Result.reply("UNDO", Reply.of(from, SmsTemplate.UNDO_DONE, "REF", u.reference()));
    }

    private Result round(Sender sender, String from) {
        SmsQueries.AgentInfo agent = queries.agent(sender.councilId(), sender.agentId()).orElseThrow();
        long[] day = queries.agentDay(sender.councilId(), sender.agentId(), today(sender.councilId()));
        Optional<String> walletId = wallets.ofAgent(sender.councilId(), sender.agentId()).map(WalletOwner::walletId);
        String count = Long.toString(day[0]);
        String total = SmsFormat.kwacha(day[1]);
        return new Result("ROUND", List.of(), List.of(() -> {
            String floatText = "not known";
            if (walletId.isPresent()) {
                try {
                    floatText = payments.findWallet(walletId.get()).map(PaymentsWallet::balanceNgwee)
                            .map(SmsFormat::kwacha).orElse("not known");
                } catch (PaymentsUnavailableException e) {
                    floatText = "not known";
                }
            }
            return List.of(Reply.of(from, SmsTemplate.ROUND, "NAME", agent.displayName(), "COUNT", count, "AMOUNT", total,
                    "AMOUNT2", floatText));
        }));
    }
}
