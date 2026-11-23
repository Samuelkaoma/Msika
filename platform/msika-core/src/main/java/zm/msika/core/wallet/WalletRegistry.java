package zm.msika.core.wallet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Which payments-system wallets belong to which stalls, agents and suppliers. Implemented in msika-api. */
public interface WalletRegistry {

    /**
     * Records a wallet.
     *
     * @return false, adding nothing, if the wallet is already registered or the stall or agent already has a wallet
     *     in that role
     */
    boolean register(WalletOwner owner);

    /**
     * Finds a wallet's owner from the wallet id alone, in ANY council. A payment callback names wallets and not
     * councils, so this is how Msika learns whose payment it is.
     */
    Optional<WalletOwner> find(String walletId);

    /** The stall's wallet in the role (TRADER or MERCHANT), within the council. */
    Optional<WalletOwner> ofStall(UUID councilId, UUID stallId, WalletOwner.Role role);

    Optional<WalletOwner> ofAgent(UUID councilId, UUID agentId);

    /** The council's collection wallet, which levy is paid into. */
    Optional<WalletOwner> ofCouncil(UUID councilId);

    List<WalletOwner> list(UUID councilId);
}
