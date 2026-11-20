package zm.msika.core.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.agent.Agent;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.ledger.LedgerFakes;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.payments.HandlePaymentEvent;
import zm.msika.core.payments.HandlePaymentEvent.Outcome;
import zm.msika.core.payments.PaymentEvent;
import zm.msika.core.payments.PaymentFakes;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallStatus;
import zm.msika.core.wallet.RegisterWallet;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Provenance;

/**
 * Specification for the deposit lifecycle (card M-15): how a deposit is started, how each status change
 * happens, and what the payments system's answer does to it, including the answer that comes after the agent
 * cancelled.
 */
class DepositLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final long MAX = 5_000_000;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Fakes.Councils councils = new Fakes.Councils();
    private final Fakes.Stalls stalls = new Fakes.Stalls();
    private final DepositFakes.Agents agents = new DepositFakes.Agents();
    private final DepositFakes.Wallets wallets = new DepositFakes.Wallets();
    private final DepositFakes.Deposits deposits = new DepositFakes.Deposits();
    private final PaymentFakes.Requests requests = new PaymentFakes.Requests();
    private final LedgerFakes.Ledger ledger = new LedgerFakes.Ledger();

    private Council council;
    private Agent agent;
    private Stall stall;
    private RequestDeposit requestDeposit;
    private ChangeDeposit changeDeposit;
    private HandlePaymentEvent handler;

    @BeforeEach
    void setUp() {
        council = new Council(UUID.randomUUID(), "Lusaka City Council", ZoneId.of("Africa/Lusaka"),
                Instant.parse("2026-11-20T08:00:00Z"));
        councils.add(council);
        agent = new Agent(UUID.randomUUID(), council.id(), new PhoneNumber("+260977000001"), "Test Agent", true,
                Instant.parse("2026-11-20T08:00:00Z"));
        agents.add(agent);
        stall = new Stall(UUID.randomUUID(), council.id(), UUID.randomUUID(), "SOW-B-114", "STANDARD",
                StallStatus.ALLOCATED, "t".repeat(22));
        stalls.addAll(List.of(stall));
        register(WalletOwner.Role.AGENT, "w-agent", null, agent.id());
        register(WalletOwner.Role.TRADER, "w-trader", stall.id(), null);

        requestDeposit = new RequestDeposit(councils, stalls, agents, wallets, deposits, requests, clock, new Random(7), MAX);
        changeDeposit = new ChangeDeposit(deposits, clock, events);
        RecordEntry recordEntry = new RecordEntry(councils, stalls, ledger, new LedgerFakes.Seals(), new LedgerFakes.Lock());
        handler = new HandlePaymentEvent(requests, recordEntry, clock, new DepositOutcomes(deposits, clock, events));
    }

    /** Records what people would have been told (card M-19). */
    private final List<String> told = new java.util.ArrayList<>();
    private final DepositEvents events = new DepositEvents() {
        @Override
        public void confirmed(Deposit d) {
            told.add("confirmed " + d.reference() + " " + d.status());
        }

        @Override
        public void failed(Deposit d, String reason) {
            told.add("failed " + d.reference() + " " + reason);
        }

        @Override
        public void cancelled(Deposit d) {
            told.add("cancelled " + d.reference());
        }
    };

    @Nested
    @DisplayName("who is told (card M-19)")
    class Told {

        @Test
        @DisplayName("the trader is told once when money is confirmed, however many times the callback comes")
        void confirmedOnce() {
            RequestDeposit.Prepared p = start("k", 45_000);
            handler.execute(succeeded(p, "tx-1"));
            handler.execute(succeeded(p, "tx-1"));

            assertThat(told).containsExactly("confirmed " + p.deposit().reference() + " CONFIRMED");
        }

        @Test
        @DisplayName("the agent is told once when it fails")
        void failedOnce() {
            RequestDeposit.Prepared p = start("k", 45_000);
            handler.execute(failedEvent(p, "tx-1"));
            handler.execute(failedEvent(p, "tx-1"));

            assertThat(told).containsExactly("failed " + p.deposit().reference() + " INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("a cancel is told, and money that arrives after it is told as well (the trader can say NO)")
        void cancelThenPaid() {
            RequestDeposit.Prepared p = start("k", 45_000);
            changeDeposit.cancel(council.id(), p.deposit().id());
            handler.execute(succeeded(p, "tx-1"));

            assertThat(told).containsExactly("cancelled " + p.deposit().reference(),
                    "confirmed " + p.deposit().reference() + " DISPUTED");
        }

        @Test
        @DisplayName("a failure after a cancel tells nobody: nothing happened")
        void cancelThenFailed() {
            RequestDeposit.Prepared p = start("k", 45_000);
            changeDeposit.cancel(council.id(), p.deposit().id());
            handler.execute(failedEvent(p, "tx-1"));

            assertThat(told).containsExactly("cancelled " + p.deposit().reference());
        }
    }

    private void register(WalletOwner.Role role, String id, UUID stallId, UUID agentId) {
        new RegisterWallet(wallets, stalls, agents).execute(council.id(), id, role, stallId, agentId);
    }

    private RequestDeposit.Prepared start(String key, long ngwee) {
        return requestDeposit.execute(council.id(), agent.id(), stall.id(), ngwee, key);
    }

    private PaymentEvent succeeded(RequestDeposit.Prepared p, String txn) {
        return new PaymentEvent("cb-" + txn, txn, PaymentEvent.Status.SUCCEEDED, p.request().amount().minorUnits(),
                p.request().reference(), null, NOW, "CASH_IN", "w-agent", "w-trader");
    }

    private PaymentEvent failedEvent(RequestDeposit.Prepared p, String txn) {
        return new PaymentEvent("cb-" + txn, txn, PaymentEvent.Status.FAILED, p.request().amount().minorUnits(),
                p.request().reference(), "INSUFFICIENT_FUNDS", NOW, "CASH_IN", "w-agent", "w-trader");
    }

    private Deposit now(RequestDeposit.Prepared p) {
        return deposits.byId.get(p.deposit().id());
    }

    @Nested
    @DisplayName("starting a deposit")
    class Starting {

        @Test
        @DisplayName("saves a PENDING deposit and a SENT payment request, with the wallets to use")
        void creates() {
            RequestDeposit.Prepared p = start("key-1", 45_000);

            assertThat(p.created()).isTrue();
            assertThat(p.deposit().status()).isEqualTo(DepositStatus.PENDING);
            assertThat(p.deposit().amount().minorUnits()).isEqualTo(45_000);
            assertThat(p.deposit().stallId()).isEqualTo(stall.id());
            assertThat(p.deposit().agentId()).isEqualTo(agent.id());
            assertThat(DepositReference.isValid(p.deposit().reference())).isTrue();
            assertThat(p.deposit().paymentRequestId()).isEqualTo(p.request().id());
            assertThat(p.request().status()).isEqualTo(PaymentRequest.Status.SENT);
            assertThat(p.request().stallId()).isEqualTo(stall.id());
            assertThat(p.agentWalletId()).isEqualTo("w-agent");
            assertThat(p.traderWalletId()).isEqualTo("w-trader");
            assertThat(ledger.entries).as("nothing is on the ledger until the payment is confirmed").isEmpty();
        }

        @Test
        @DisplayName("the business date is the Lusaka date, not the UTC date")
        void businessDate() {
            Clock lateEvening = Clock.fixed(Instant.parse("2026-11-24T23:30:00Z"), ZoneOffset.UTC);
            RequestDeposit late = new RequestDeposit(councils, stalls, agents, wallets, deposits, requests, lateEvening,
                    new Random(1), MAX);

            Deposit d = late.execute(council.id(), agent.id(), stall.id(), 1_000, "late").deposit();

            assertThat(d.businessDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        }

        @Test
        @DisplayName("the same Idempotency-Key twice is one deposit and one payment request")
        void idempotent() {
            RequestDeposit.Prepared first = start("same-key", 45_000);
            RequestDeposit.Prepared again = start("same-key", 45_000);
            RequestDeposit.Prepared differentAmount = start("same-key", 99_000);

            assertThat(again.created()).isFalse();
            assertThat(again.deposit().id()).isEqualTo(first.deposit().id());
            assertThat(differentAmount.deposit().id()).as("the first one wins, whatever the repeat says").isEqualTo(first.deposit().id());
            assertThat(differentAmount.deposit().amount().minorUnits()).isEqualTo(45_000);
            assertThat(deposits.byId).hasSize(1);
            assertThat(requests.byId).hasSize(1);
            assertThat(again.request().id()).isEqualTo(first.request().id());
        }

        @Test
        @DisplayName("two deposits the same day get different references")
        void referencesDiffer() {
            Deposit a = start("a", 1_000).deposit();
            Deposit b = start("b", 1_000).deposit();

            assertThat(a.reference()).isNotEqualTo(b.reference());
        }

        @Test
        @DisplayName("when every reference it tries is taken, it gives up with a conflict and leaves no extra deposit")
        void referencesExhausted() {
            Random constant = new Random() {
                @Override
                public int nextInt(int bound) {
                    return 0;
                }
            };
            RequestDeposit stuck = new RequestDeposit(councils, stalls, agents, wallets, deposits, requests, clock, constant, MAX);
            stuck.execute(council.id(), agent.id(), stall.id(), 1_000, "one");

            assertThatThrownBy(() -> stuck.execute(council.id(), agent.id(), stall.id(), 1_000, "two"))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("reference");
            assertThat(deposits.byId).hasSize(1);
        }

        @Test
        @DisplayName("refuses bad input: amount, key, inactive agent, unknown agent or stall, vacant stall, missing wallets")
        void validation() {
            assertThatThrownBy(() -> start("k", 0)).isInstanceOf(InvalidInputException.class).hasMessageContaining("greater than zero");
            assertThatThrownBy(() -> start("k", -5)).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> start("k", MAX + 1)).isInstanceOf(InvalidInputException.class).hasMessageContaining("limit");
            assertThat(start("max", MAX).created()).as("exactly the limit is allowed").isTrue();
            assertThatThrownBy(() -> start(null, 100)).isInstanceOf(InvalidInputException.class).hasMessageContaining("Idempotency-Key");
            assertThatThrownBy(() -> start("  ", 100)).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> start("x".repeat(101), 100)).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> requestDeposit.execute(council.id(), UUID.randomUUID(), stall.id(), 100, "k"))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> requestDeposit.execute(council.id(), agent.id(), UUID.randomUUID(), 100, "k"))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> requestDeposit.execute(UUID.randomUUID(), agent.id(), stall.id(), 100, "k"))
                    .isInstanceOf(NotFoundException.class);

            stalls.setStatus(council.id(), stall.id(), StallStatus.VACANT);
            assertThatThrownBy(() -> start("vacant", 100)).isInstanceOf(InvalidInputException.class).hasMessageContaining("no trader");
            stalls.setStatus(council.id(), stall.id(), StallStatus.ALLOCATED);

            Agent inactive = new Agent(UUID.randomUUID(), council.id(), new PhoneNumber("+260977000002"), "Stopped", false, NOW);
            agents.add(inactive);
            assertThatThrownBy(() -> requestDeposit.execute(council.id(), inactive.id(), stall.id(), 100, "k"))
                    .isInstanceOf(InvalidInputException.class).hasMessageContaining("not active");

            Agent noWallet = new Agent(UUID.randomUUID(), council.id(), new PhoneNumber("+260977000003"), "No Wallet", true, NOW);
            agents.add(noWallet);
            assertThatThrownBy(() -> requestDeposit.execute(council.id(), noWallet.id(), stall.id(), 100, "k"))
                    .isInstanceOf(InvalidInputException.class).hasMessageContaining("no payments wallet");

            Stall noTraderWallet = new Stall(UUID.randomUUID(), council.id(), stall.marketId(), "SOW-B-115", "STANDARD",
                    StallStatus.ALLOCATED, "u".repeat(22));
            stalls.addAll(List.of(noTraderWallet));
            assertThatThrownBy(() -> requestDeposit.execute(council.id(), agent.id(), noTraderWallet.id(), 100, "k"))
                    .isInstanceOf(InvalidInputException.class).hasMessageContaining("no trader wallet");
            assertThat(requests.byId.size()).as("a refused deposit leaves no request behind").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("what the payments system's answer does")
    class Answers {

        @Test
        @DisplayName("PENDING to CONFIRMED when the payment succeeds, linked to its DEPOSITED ledger entry")
        void confirmed() {
            RequestDeposit.Prepared p = start("k", 45_000);

            assertThat(handler.execute(succeeded(p, "tx-1"))).isEqualTo(Outcome.RECORDED);

            Deposit d = now(p);
            assertThat(d.status()).isEqualTo(DepositStatus.CONFIRMED);
            assertThat(ledger.entries).hasSize(1);
            LedgerEntry entry = ledger.entries.get(0);
            assertThat(entry.kind()).isEqualTo(EntryKind.DEPOSIT);
            assertThat(entry.provenance()).isEqualTo(Provenance.DEPOSITED);
            assertThat(d.ledgerEntryId()).isEqualTo(entry.id());
            assertThat(d.updatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("PENDING to FAILED when the payment fails, with the reason, and nothing on the ledger")
        void failed() {
            RequestDeposit.Prepared p = start("k", 45_000);

            assertThat(handler.execute(failedEvent(p, "tx-1"))).isEqualTo(Outcome.FAILURE_NOTED);

            assertThat(now(p).status()).isEqualTo(DepositStatus.FAILED);
            assertThat(now(p).statusReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(ledger.entries).isEmpty();
        }

        @Test
        @DisplayName("a repeated confirmation changes nothing: still CONFIRMED, one entry")
        void repeated() {
            RequestDeposit.Prepared p = start("k", 45_000);
            handler.execute(succeeded(p, "tx-1"));

            assertThat(handler.execute(succeeded(p, "tx-1"))).isEqualTo(Outcome.DUPLICATE);

            assertThat(now(p).status()).isEqualTo(DepositStatus.CONFIRMED);
            assertThat(ledger.entries).hasSize(1);
        }

        @Test
        @DisplayName("a failure after a confirmation (or the other way round) cannot change the first outcome")
        void firstOutcomeWins() {
            RequestDeposit.Prepared ok = start("ok", 1_000);
            handler.execute(succeeded(ok, "tx-ok"));
            handler.execute(failedEvent(ok, "tx-ok"));
            assertThat(now(ok).status()).isEqualTo(DepositStatus.CONFIRMED);

            RequestDeposit.Prepared bad = start("bad", 2_000);
            handler.execute(failedEvent(bad, "tx-bad"));
            handler.execute(succeeded(bad, "tx-bad"));
            assertThat(now(bad).status()).isEqualTo(DepositStatus.FAILED);
            assertThat(ledger.entries).hasSize(1);
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancelling {

        @Test
        @DisplayName("PENDING to CANCELLED")
        void cancel() {
            RequestDeposit.Prepared p = start("k", 45_000);

            Deposit cancelled = changeDeposit.cancel(council.id(), p.deposit().id());

            assertThat(cancelled.status()).isEqualTo(DepositStatus.CANCELLED);
            assertThat(cancelled.statusReason()).isEqualTo("CANCELLED_BY_AGENT");
        }

        @Test
        @DisplayName("a callback that arrives AFTER the cancel and says it succeeded: the money moved, so it is recorded and the deposit is DISPUTED")
        void paidAfterCancel() {
            RequestDeposit.Prepared p = start("k", 45_000);
            changeDeposit.cancel(council.id(), p.deposit().id());

            Outcome outcome = handler.execute(succeeded(p, "tx-late"));

            assertThat(outcome).isEqualTo(Outcome.RECORDED);
            assertThat(ledger.entries).as("the ledger follows what really happened").hasSize(1);
            assertThat(now(p).status()).isEqualTo(DepositStatus.DISPUTED);
            assertThat(now(p).statusReason()).isEqualTo(DepositOutcomes.PAID_AFTER_CANCEL);
            assertThat(now(p).ledgerEntryId()).isEqualTo(ledger.entries.get(0).id());
        }

        @Test
        @DisplayName("a callback after the cancel that says it failed leaves the deposit CANCELLED and the ledger empty")
        void failedAfterCancel() {
            RequestDeposit.Prepared p = start("k", 45_000);
            changeDeposit.cancel(council.id(), p.deposit().id());

            handler.execute(failedEvent(p, "tx-1"));

            assertThat(now(p).status()).isEqualTo(DepositStatus.CANCELLED);
            assertThat(ledger.entries).isEmpty();
        }

        @Test
        @DisplayName("a late confirmation delivered twice still records one entry and stays DISPUTED")
        void paidAfterCancelTwice() {
            RequestDeposit.Prepared p = start("k", 45_000);
            changeDeposit.cancel(council.id(), p.deposit().id());

            handler.execute(succeeded(p, "tx-late"));
            assertThat(handler.execute(succeeded(p, "tx-late"))).isEqualTo(Outcome.DUPLICATE);

            assertThat(ledger.entries).hasSize(1);
            assertThat(now(p).status()).isEqualTo(DepositStatus.DISPUTED);
        }

        @Test
        @DisplayName("a deposit that is already CONFIRMED, FAILED or CANCELLED cannot be cancelled")
        void cannotCancel() {
            RequestDeposit.Prepared confirmed = start("a", 1_000);
            handler.execute(succeeded(confirmed, "tx-a"));
            RequestDeposit.Prepared failed = start("b", 1_000);
            handler.execute(failedEvent(failed, "tx-b"));
            RequestDeposit.Prepared cancelled = start("c", 1_000);
            changeDeposit.cancel(council.id(), cancelled.deposit().id());

            for (RequestDeposit.Prepared p : List.of(confirmed, failed, cancelled)) {
                assertThatThrownBy(() -> changeDeposit.cancel(council.id(), p.deposit().id()))
                        .isInstanceOf(ConflictException.class).hasMessageContaining("PENDING");
            }
            assertThat(now(confirmed).status()).isEqualTo(DepositStatus.CONFIRMED);
        }
    }

    @Nested
    @DisplayName("disputing")
    class Disputing {

        @Test
        @DisplayName("CONFIRMED to DISPUTED with the reason; the ledger is untouched")
        void dispute() {
            RequestDeposit.Prepared p = start("k", 45_000);
            handler.execute(succeeded(p, "tx-1"));

            Deposit disputed = changeDeposit.dispute(council.id(), p.deposit().id(), " I gave 400, not 450 ");

            assertThat(disputed.status()).isEqualTo(DepositStatus.DISPUTED);
            assertThat(disputed.statusReason()).isEqualTo("I gave 400, not 450");
            assertThat(ledger.entries).hasSize(1);
        }

        @Test
        @DisplayName("with no reason given, the reason says the trader disputed it")
        void defaultReason() {
            RequestDeposit.Prepared p = start("k", 45_000);
            handler.execute(succeeded(p, "tx-1"));

            assertThat(changeDeposit.dispute(council.id(), p.deposit().id(), null).statusReason())
                    .isEqualTo("DISPUTED_BY_TRADER");
        }

        @Test
        @DisplayName("only a CONFIRMED deposit can be disputed: not PENDING, FAILED, CANCELLED or already DISPUTED")
        void onlyConfirmed() {
            RequestDeposit.Prepared pending = start("a", 1_000);
            RequestDeposit.Prepared failed = start("b", 1_000);
            handler.execute(failedEvent(failed, "tx-b"));
            RequestDeposit.Prepared cancelled = start("c", 1_000);
            changeDeposit.cancel(council.id(), cancelled.deposit().id());
            RequestDeposit.Prepared disputed = start("d", 1_000);
            handler.execute(succeeded(disputed, "tx-d"));
            changeDeposit.dispute(council.id(), disputed.deposit().id(), null);

            for (RequestDeposit.Prepared p : List.of(pending, failed, cancelled, disputed)) {
                assertThatThrownBy(() -> changeDeposit.dispute(council.id(), p.deposit().id(), null))
                        .isInstanceOf(ConflictException.class).hasMessageContaining("CONFIRMED");
            }
        }

        @Test
        @DisplayName("an unknown deposit is not found, and a reason that is too long is refused")
        void errors() {
            assertThatThrownBy(() -> changeDeposit.dispute(council.id(), UUID.randomUUID(), null)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> changeDeposit.cancel(council.id(), UUID.randomUUID())).isInstanceOf(NotFoundException.class);
            RequestDeposit.Prepared p = start("k", 1_000);
            handler.execute(succeeded(p, "tx-1"));
            assertThatThrownBy(() -> changeDeposit.dispute(council.id(), p.deposit().id(), "x".repeat(201)))
                    .isInstanceOf(InvalidInputException.class);
            assertThat(now(p).status()).isEqualTo(DepositStatus.CONFIRMED);
        }
    }

    @Test
    @DisplayName("references use only the SMS alphabet: no 0, O, 1, I or L")
    void referenceAlphabet() {
        Random random = new Random(1);
        for (int i = 0; i < 2_000; i++) {
            String ref = DepositReference.generate(random);
            assertThat(DepositReference.isValid(ref)).as(ref).isTrue();
            assertThat(ref).doesNotContain("0", "O", "1", "I", "L");
        }
        assertThat(DepositReference.isValid("8F2K")).isTrue();
        assertThat(DepositReference.isValid("8f2k")).as("upper case only").isFalse();
        assertThat(DepositReference.isValid("8F2")).isFalse();
        assertThat(DepositReference.isValid("8F2KK")).isFalse();
        assertThat(DepositReference.isValid("8F2O")).isFalse();
        assertThat(DepositReference.isValid(null)).isFalse();
    }
}
