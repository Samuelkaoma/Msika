package zm.msika.core.wallet;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a wallet in the payments system belongs to, as far as Msika is concerned. Wallet ids are the payments
 * system's own and Msika never interprets them.
 *
 * @param walletId the payments system's id for the wallet
 * @param councilId the council it belongs to
 * @param role what the wallet is for
 * @param stallId the stall, for TRADER and MERCHANT wallets; otherwise {@code null}
 * @param agentId the agent, for AGENT wallets; otherwise {@code null}
 */
public record WalletOwner(String walletId, UUID councilId, Role role, UUID stallId, UUID agentId) {

    public enum Role {
        /** The trader's own wallet: closing-time deposits are paid into it. One per stall. */
        TRADER,
        /** The wallet customers pay for goods: its payments are the stall's VERIFIED sales. One per stall. */
        MERCHANT,
        /** A deposit agent's float wallet: cash-ins are paid out of it. One per agent. */
        AGENT,
        /** A supplier's wallet: a payment to it from a stall is a VERIFIED supplier payment. */
        SUPPLIER
    }

    public WalletOwner {
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        boolean needsStall = role == Role.TRADER || role == Role.MERCHANT;
        if (needsStall != (stallId != null) || (role == Role.AGENT) != (agentId != null)) {
            throw new IllegalArgumentException("a " + role + " wallet " + (needsStall ? "needs a stall" : role == Role.AGENT
                    ? "needs an agent and no stall" : "has neither stall nor agent"));
        }
        if (walletId.isBlank() || walletId.length() > 100) {
            throw new IllegalArgumentException("walletId must be 1 to 100 characters");
        }
    }
}
