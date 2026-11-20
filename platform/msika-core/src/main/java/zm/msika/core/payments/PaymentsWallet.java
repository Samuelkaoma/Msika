package zm.msika.core.payments;

import java.util.Objects;

/**
 * What the payments system says about one of its wallets: what kind it is and the phone number it is registered
 * to. Msika uses this to check a wallet really belongs to whoever a council says it does before recording it
 * (see {@link zm.msika.core.wallet.VerifyWalletOwnership}).
 *
 * @param walletId the payments system's own id for the wallet
 * @param kind the kind of wallet, mapped by the adapter from the payments system's own types
 * @param msisdn the phone number the wallet is registered to, in E.164 form, or {@code null} if it has none
 * @param balanceNgwee the wallet's balance in whole ngwee, or {@code null} if the payments system did not say
 */
public record PaymentsWallet(String walletId, Kind kind, String msisdn, Long balanceNgwee) {

    /** A wallet whose balance is not known. */
    public PaymentsWallet(String walletId, Kind kind, String msisdn) {
        this(walletId, kind, msisdn, null);
    }

    /** The kinds Msika cares about. Anything else (a council, lender or issuer wallet) is {@link #OTHER}. */
    public enum Kind {
        TRADER, MERCHANT, AGENT, SUPPLIER, OTHER
    }

    public PaymentsWallet {
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }
}
