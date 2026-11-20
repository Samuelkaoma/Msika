package zm.msika.core.wallet;

import java.util.Objects;
import java.util.UUID;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.stall.StallRepository;

/** Use case: tell Msika that a wallet in the payments system belongs to a stall, an agent or a supplier. */
public final class RegisterWallet {

    private final WalletRegistry wallets;
    private final StallRepository stalls;
    private final AgentRepository agents;

    public RegisterWallet(WalletRegistry wallets, StallRepository stalls, AgentRepository agents) {
        this.wallets = Objects.requireNonNull(wallets);
        this.stalls = Objects.requireNonNull(stalls);
        this.agents = Objects.requireNonNull(agents);
    }

    /**
     * @throws NotFoundException if the stall or agent is not in the council
     * @throws ConflictException if the wallet is already registered, or the stall or agent already has one
     */
    public WalletOwner execute(UUID councilId, String walletId, WalletOwner.Role role, UUID stallId, UUID agentId) {
        WalletOwner owner;
        try {
            owner = new WalletOwner(walletId == null ? null : walletId.trim(), councilId, role, stallId, agentId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidInputException(e.getMessage());
        }
        if (stallId != null && stalls.find(councilId, stallId).isEmpty()) {
            throw new NotFoundException("no stall " + stallId + " in this council");
        }
        if (agentId != null && agents.find(councilId, agentId).isEmpty()) {
            throw new NotFoundException("no agent " + agentId + " in this council");
        }
        if (!wallets.register(owner)) {
            throw new ConflictException("that wallet is already registered, or this stall, agent or role already has one");
        }
        return owner;
    }
}
