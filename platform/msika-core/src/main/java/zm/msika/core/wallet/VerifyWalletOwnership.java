package zm.msika.core.wallet;

import java.util.Objects;
import java.util.UUID;
import zm.msika.core.agent.Agent;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.stall.StallAllocation;
import zm.msika.core.stall.StallAllocationRepository;
import zm.msika.core.trader.Trader;
import zm.msika.core.trader.TraderRepository;

/**
 * Use case: before a wallet is registered, check with what the payments system says about it that it really is
 * the wallet the council claims.
 *
 * <p>Wallet ids are global: whichever council registers an id first owns it in Msika, and every payment into it
 * is recorded on that council's ledger. Without this check, a council could claim another council's trader's
 * wallet, by mistake or on purpose. So:
 *
 * <ul>
 *   <li>the wallet must exist in the payments system, and be the kind the role says;
 *   <li>a TRADER wallet must be registered to the phone of the trader who holds the stall now;
 *   <li>an AGENT wallet must be registered to the agent's phone.
 * </ul>
 *
 * <p>MERCHANT and SUPPLIER wallets are only checked for existence and kind. In the simulator a merchant wallet
 * cannot share the trader's phone number (every phone has one wallet), and suppliers are not people Msika knows.
 * Proving who owns those needs the real payments system's owner records (card M-46).
 *
 * <p>Call it inside a transaction for the council. The lookup in the payments system happens before, outside any
 * transaction, and its answer is passed in.
 */
public final class VerifyWalletOwnership {

    private final StallAllocationRepository allocations;
    private final TraderRepository traders;
    private final AgentRepository agents;

    public VerifyWalletOwnership(StallAllocationRepository allocations, TraderRepository traders,
            AgentRepository agents) {
        this.allocations = Objects.requireNonNull(allocations);
        this.traders = Objects.requireNonNull(traders);
        this.agents = Objects.requireNonNull(agents);
    }

    /**
     * @param wallet what the payments system said about the wallet, or {@code null} if it has no such wallet
     * @throws InvalidInputException if the wallet does not exist, is the wrong kind, or belongs to another phone
     * @throws NotFoundException if the agent is not in the council
     */
    public void check(UUID councilId, String walletId, WalletOwner.Role role, UUID stallId, UUID agentId,
            PaymentsWallet wallet) {
        if (wallet == null) {
            throw new InvalidInputException("the payments system has no wallet " + walletId);
        }
        if (!wallet.kind().name().equals(role.name())) {
            throw new InvalidInputException("wallet " + walletId + " is a " + wallet.kind() + " wallet, not " + role);
        }
        switch (role) {
            case TRADER -> {
                StallAllocation allocation = allocations.findActive(councilId, stallId)
                        .orElseThrow(() -> new InvalidInputException(
                                "this stall has no trader; allocate it before registering its trader wallet"));
                Trader trader = traders.find(councilId, allocation.traderId())
                        .orElseThrow(() -> new NotFoundException("the stall's trader was not found"));
                requireSamePhone(wallet, trader.phone(), "the trader who holds this stall");
            }
            case AGENT -> {
                Agent agent = agents.find(councilId, agentId)
                        .orElseThrow(() -> new NotFoundException("no agent " + agentId + " in this council"));
                requireSamePhone(wallet, agent.phone(), "this agent");
            }
            case MERCHANT, SUPPLIER, COUNCIL -> {
                // Existence and kind only: see the class comment.
            }
        }
    }

    private static void requireSamePhone(PaymentsWallet wallet, PhoneNumber expected, String whose) {
        if (wallet.msisdn() == null || !wallet.msisdn().equals(expected.e164())) {
            // Never print either number: the message reaches the API caller and the logs.
            throw new InvalidInputException(
                    "wallet " + wallet.walletId() + " is not registered to the phone of " + whose);
        }
    }
}
