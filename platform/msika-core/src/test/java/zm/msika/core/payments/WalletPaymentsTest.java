package zm.msika.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.agent.Agent;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.deposit.DepositFakes;
import zm.msika.core.ledger.LedgerFakes;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallStatus;
import zm.msika.core.wallet.RegisterWallet;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletOwner.Role;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Provenance;

/** Wallet registration, and recording the sales and supplier payments the payments system reports (ARCHITECTURE 4.2). */
class WalletPaymentsTest {

    private static final Instant DONE = Instant.parse("2026-11-24T10:00:00Z");

    private final Fakes.Councils councils = new Fakes.Councils();
    private final Fakes.Stalls stalls = new Fakes.Stalls();
    private final DepositFakes.Agents agents = new DepositFakes.Agents();
    private final DepositFakes.Wallets wallets = new DepositFakes.Wallets();
    private final LedgerFakes.Ledger ledger = new LedgerFakes.Ledger();

    private Council council;
    private Stall stall;
    private Agent agent;
    private RegisterWallet register;
    private RecordWalletPayment recorder;

    @BeforeEach
    void setUp() {
        council = new Council(UUID.randomUUID(), "Lusaka City Council", ZoneId.of("Africa/Lusaka"),
                Instant.parse("2026-11-20T08:00:00Z"));
        councils.add(council);
        stall = new Stall(UUID.randomUUID(), council.id(), UUID.randomUUID(), "SOW-B-114", "STANDARD",
                StallStatus.ALLOCATED, "t".repeat(22));
        stalls.addAll(List.of(stall));
        agent = new Agent(UUID.randomUUID(), council.id(), new PhoneNumber("+260977000001"), "Test Agent", true, DONE);
        agents.add(agent);
        register = new RegisterWallet(wallets, stalls, agents);
        recorder = new RecordWalletPayment(wallets,
                new RecordEntry(councils, stalls, ledger, new LedgerFakes.Seals(), new LedgerFakes.Lock()));
        register.execute(council.id(), "w-merchant", Role.MERCHANT, stall.id(), null);
        register.execute(council.id(), "w-trader", Role.TRADER, stall.id(), null);
        register.execute(council.id(), "w-supplier", Role.SUPPLIER, null, null);
        register.execute(council.id(), "w-agent", Role.AGENT, null, agent.id());
    }

    private static PaymentEvent event(String txn, String status, String kind, String from, String to, long amount) {
        return new PaymentEvent("cb-" + txn, txn, PaymentEvent.Status.valueOf(status), amount, null, null, DONE, kind, from, to);
    }

    private Optional<RecordWalletPayment.Classified> classify(PaymentEvent e) {
        return recorder.classify(e);
    }

    @Test
    @DisplayName("a payment into a stall's MERCHANT wallet is a VERIFIED MERCHANT_SALE for that stall")
    void sale() {
        PaymentEvent e = event("tx-1", "SUCCEEDED", "REQUEST_TO_PAY", "w-customer", "w-merchant", 12_000);

        RecordWalletPayment.Classified c = classify(e).orElseThrow();
        assertThat(recorder.execute(e, c)).isTrue();

        assertThat(c.owner().councilId()).isEqualTo(council.id());
        assertThat(ledger.entries).hasSize(1);
        LedgerEntry entry = ledger.entries.get(0);
        assertThat(entry.kind()).isEqualTo(EntryKind.MERCHANT_SALE);
        assertThat(entry.provenance()).isEqualTo(Provenance.VERIFIED);
        assertThat(entry.stallId()).isEqualTo(stall.id());
        assertThat(entry.amount().minorUnits()).isEqualTo(12_000);
        assertThat(entry.idempotencyKey()).isEqualTo("zamsim:tx-1");
        assertThat(entry.occurredAt()).isEqualTo(DONE);
    }

    @Test
    @DisplayName("a transfer into the merchant wallet counts as a sale too")
    void transferSale() {
        assertThat(classify(event("tx-1", "SUCCEEDED", "TRANSFER", "w-customer", "w-merchant", 500))).isPresent();
    }

    @Test
    @DisplayName("a payment from a stall's wallet (trader or merchant) to a SUPPLIER wallet is a VERIFIED SUPPLIER_PAYMENT")
    void supplierPayment() {
        for (String from : List.of("w-trader", "w-merchant")) {
            PaymentEvent e = event("tx-" + from, "SUCCEEDED", "TRANSFER", from, "w-supplier", 30_000);
            RecordWalletPayment.Classified c = classify(e).orElseThrow();
            assertThat(recorder.execute(e, c)).isTrue();
        }

        assertThat(ledger.entries).hasSize(2);
        assertThat(ledger.entries).allSatisfy(entry -> {
            assertThat(entry.kind()).isEqualTo(EntryKind.SUPPLIER_PAYMENT);
            assertThat(entry.provenance()).isEqualTo(Provenance.VERIFIED);
            assertThat(entry.stallId()).isEqualTo(stall.id());
        });
    }

    @Test
    @DisplayName("the same payment reported twice is recorded once")
    void idempotent() {
        PaymentEvent e = event("tx-1", "SUCCEEDED", "REQUEST_TO_PAY", "w-customer", "w-merchant", 12_000);
        RecordWalletPayment.Classified c = classify(e).orElseThrow();

        assertThat(recorder.execute(e, c)).isTrue();
        assertThat(recorder.execute(e, c)).as("a repeat records nothing").isFalse();
        assertThat(ledger.entries).hasSize(1);
    }

    @Test
    @DisplayName("everything else is ignored: failures, cash-ins, funding, unknown wallets, agents, other directions")
    void ignored() {
        assertThat(classify(event("1", "FAILED", "REQUEST_TO_PAY", "w-customer", "w-merchant", 100))).as("failed").isEmpty();
        assertThat(classify(event("2", "SUCCEEDED", "CASH_IN", "w-agent", "w-merchant", 100))).as("cash-in").isEmpty();
        assertThat(classify(event("3", "SUCCEEDED", "FUND", "w-issuer", "w-merchant", 100))).as("fund").isEmpty();
        assertThat(classify(event("4", "SUCCEEDED", "WITHDRAW", "w-merchant", "w-issuer", 100))).as("withdraw").isEmpty();
        assertThat(classify(event("5", "SUCCEEDED", "TRANSFER", "w-a", "w-unknown", 100))).as("unknown wallet").isEmpty();
        assertThat(classify(event("6", "SUCCEEDED", "TRANSFER", "w-merchant", "w-trader", 100)))
                .as("between a stall's own wallets").isEmpty();
        assertThat(classify(event("7", "SUCCEEDED", "TRANSFER", "w-agent", "w-supplier", 100)))
                .as("an agent paying a supplier is not a stall's purchase").isEmpty();
        assertThat(classify(event("8", "SUCCEEDED", "TRANSFER", "w-unknown", "w-supplier", 100))).as("unknown payer").isEmpty();
        assertThat(classify(event("9", "SUCCEEDED", null, "w-customer", "w-merchant", 100))).as("no kind").isEmpty();
        assertThat(classify(event("10", "SUCCEEDED", "TRANSFER", "w-customer", null, 100))).as("no destination").isEmpty();
        assertThat(ledger.entries).isEmpty();
    }

    @Test
    @DisplayName("a supplier payment from another council's stall is not attributed across councils")
    void acrossCouncils() {
        Council other = new Council(UUID.randomUUID(), "Other Council", ZoneId.of("Africa/Lusaka"), DONE);
        councils.add(other);
        Stall otherStall = new Stall(UUID.randomUUID(), other.id(), UUID.randomUUID(), "OTH-A-1", "STANDARD",
                StallStatus.ALLOCATED, "o".repeat(22));
        stalls.addAll(List.of(otherStall));
        register.execute(other.id(), "w-other-trader", Role.TRADER, otherStall.id(), null);

        assertThat(classify(event("1", "SUCCEEDED", "TRANSFER", "w-other-trader", "w-supplier", 100))).isEmpty();
    }

    @Test
    @DisplayName("registering a wallet: refuses a duplicate id, a second wallet in a role, an unknown stall or agent, and a wrong shape")
    void registration() {
        assertThatThrownBy(() -> register.execute(council.id(), "w-trader", Role.TRADER, stall.id(), null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> register.execute(council.id(), "w-trader-2", Role.TRADER, stall.id(), null))
                .as("a stall has one trader wallet").isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> register.execute(council.id(), "w-new", Role.TRADER, UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> register.execute(council.id(), "w-new", Role.AGENT, null, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> register.execute(council.id(), "w-new", Role.TRADER, null, null))
                .isInstanceOf(InvalidInputException.class).hasMessageContaining("needs a stall");
        assertThatThrownBy(() -> register.execute(council.id(), "w-new", Role.AGENT, stall.id(), null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> register.execute(council.id(), "w-new", Role.SUPPLIER, stall.id(), null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> register.execute(council.id(), "  ", Role.SUPPLIER, null, null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> register.execute(council.id(), null, Role.SUPPLIER, null, null))
                .isInstanceOf(InvalidInputException.class);
        assertThat(wallets.all).hasSize(4);
    }
}
