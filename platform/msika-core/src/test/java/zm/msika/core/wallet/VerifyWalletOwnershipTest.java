package zm.msika.core.wallet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.agent.Agent;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.deposit.DepositFakes;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.payments.PaymentsWallet.Kind;
import zm.msika.core.phone.Language;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.stall.StallAllocation;
import zm.msika.core.trader.Trader;
import zm.msika.core.trader.TraderRepository;
import zm.msika.core.wallet.WalletOwner.Role;

/** A wallet is registered only if the payments system says it is that kind, and that person's phone. */
class VerifyWalletOwnershipTest {

    private static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");
    private static final String TRADER_PHONE = "+260977000101";
    private static final String AGENT_PHONE = "+260977000102";
    private static final String SOMEONE_ELSE = "+260977000199";

    private final Fakes.Allocations allocations = new Fakes.Allocations();
    private final DepositFakes.Agents agents = new DepositFakes.Agents();
    private final List<Trader> traderStore = new ArrayList<>();
    private final TraderRepository traders = new TraderRepository() {
        @Override
        public void add(Trader trader) {
            traderStore.add(trader);
        }

        @Override
        public Optional<Trader> find(UUID councilId, UUID traderId) {
            return traderStore.stream().filter(t -> t.councilId().equals(councilId) && t.id().equals(traderId)).findFirst();
        }

        @Override
        public List<Trader> list(UUID councilId) {
            return traderStore.stream().filter(t -> t.councilId().equals(councilId)).toList();
        }

        @Override
        public boolean existsByPhone(UUID councilId, PhoneNumber phone) {
            return traderStore.stream().anyMatch(t -> t.councilId().equals(councilId) && t.phone().equals(phone));
        }
    };

    private final VerifyWalletOwnership verify = new VerifyWalletOwnership(allocations, traders, agents);

    private UUID council;
    private UUID stall;
    private UUID emptyStall;
    private UUID agent;

    @BeforeEach
    void setUp() {
        council = UUID.randomUUID();
        stall = UUID.randomUUID();
        emptyStall = UUID.randomUUID();
        Trader trader = new Trader(UUID.randomUUID(), council, new PhoneNumber(TRADER_PHONE), "Test Trader",
                Language.ENGLISH, null, null, AT);
        traders.add(trader);
        allocations.add(new StallAllocation(UUID.randomUUID(), council, stall, trader.id(), AT, null));
        Agent a = new Agent(UUID.randomUUID(), council, new PhoneNumber(AGENT_PHONE), "Test Agent", true, AT);
        agents.add(a);
        agent = a.id();
    }

    private void check(Role role, UUID stallId, UUID agentId, PaymentsWallet wallet) {
        verify.check(council, "w-1", role, stallId, agentId, wallet);
    }

    private static PaymentsWallet wallet(Kind kind, String msisdn) {
        return new PaymentsWallet("w-1", kind, msisdn);
    }

    @Test
    @DisplayName("a trader wallet on the phone of the trader who holds the stall is accepted")
    void traderWalletOfTheHolder() {
        assertThatCode(() -> check(Role.TRADER, stall, null, wallet(Kind.TRADER, TRADER_PHONE))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a trader wallet on someone else's phone is refused, and the message names no phone number")
    void traderWalletOfSomeoneElse() {
        assertThatThrownBy(() -> check(Role.TRADER, stall, null, wallet(Kind.TRADER, SOMEONE_ELSE)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("not registered to the phone of the trader")
                .hasMessageNotContaining("0001")
                .hasMessageNotContaining("0199");
        assertThatThrownBy(() -> check(Role.TRADER, stall, null, wallet(Kind.TRADER, null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    @DisplayName("a trader wallet for a stall nobody holds is refused")
    void traderWalletForAnEmptyStall() {
        assertThatThrownBy(() -> check(Role.TRADER, emptyStall, null, wallet(Kind.TRADER, TRADER_PHONE)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("no trader");
    }

    @Test
    @DisplayName("an agent wallet must be on the agent's phone")
    void agentWallet() {
        assertThatCode(() -> check(Role.AGENT, null, agent, wallet(Kind.AGENT, AGENT_PHONE))).doesNotThrowAnyException();
        assertThatThrownBy(() -> check(Role.AGENT, null, agent, wallet(Kind.AGENT, TRADER_PHONE)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    @DisplayName("a wallet the payments system does not have is refused")
    void unknownWallet() {
        assertThatThrownBy(() -> check(Role.SUPPLIER, null, null, null))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("has no wallet");
    }

    @Test
    @DisplayName("a wallet of another kind is refused, whatever its phone")
    void wrongKind() {
        assertThatThrownBy(() -> check(Role.TRADER, stall, null, wallet(Kind.MERCHANT, TRADER_PHONE)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("MERCHANT");
        assertThatThrownBy(() -> check(Role.SUPPLIER, null, null, wallet(Kind.OTHER, null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    @DisplayName("merchant and supplier wallets are checked for kind only")
    void merchantAndSupplier() {
        assertThatCode(() -> check(Role.MERCHANT, stall, null, wallet(Kind.MERCHANT, null))).doesNotThrowAnyException();
        assertThatCode(() -> check(Role.SUPPLIER, null, null, wallet(Kind.SUPPLIER, SOMEONE_ELSE))).doesNotThrowAnyException();
    }
}
