package zm.msika.core.levy;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What Msika needs from the levy authority's system: eLevy, simulated today by {@code elevysim} (AGENTS.md rule 6).
 * Every write carries an idempotency key, because a reply can be lost and the call sent again.
 */
public interface LevyAuthorityPort {

    /** A trader's levy account as the authority sees it. */
    record LevyAccount(String accountId, long dailyLevyNgwee, long balanceNgwee, long arrearsNgwee, LocalDate paidTo) {}

    /**
     * Finds the trader's account at the market, or opens one. Safe to call twice.
     *
     * @return the authority's id for the account
     * @throws LevyAuthorityUnavailableException if the authority could not be reached or refused
     */
    String openAccount(String councilRef, String marketRef, String traderRef, long dailyLevyNgwee);

    /**
     * Credits the account after the money has moved. The same key credits once.
     *
     * @throws LevyAuthorityUnavailableException if the authority could not be reached or refused
     */
    void topUp(String accountId, String idempotencyKey, long amountNgwee, String reference);

    /** The account, or empty if the authority has no such account. */
    Optional<LevyAccount> account(String accountId);
}
