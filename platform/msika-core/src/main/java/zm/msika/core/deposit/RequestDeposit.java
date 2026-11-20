package zm.msika.core.deposit;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import zm.msika.core.agent.Agent;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallRepository;
import zm.msika.core.stall.StallStatus;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.Money;

/**
 * Use case, first half: an agent says they took cash for a stall. This checks everything that can be checked,
 * and saves a PENDING deposit together with the payment request that asks the payments system to move the money.
 * The caller then sends the request (outside any database transaction) and the outcome arrives by callback
 * (see {@link zm.msika.core.payments.HandlePaymentEvent}).
 *
 * <p>The agent's command carries an idempotency key. Sending it again returns the same deposit and makes no
 * second one, so a lost reply can never turn into two payments for one handful of cash.
 */
public final class RequestDeposit {

    private static final int REFERENCE_ATTEMPTS = 50;

    /**
     * @param deposit the deposit, PENDING when new
     * @param request its payment request
     * @param agentWalletId the wallet the cash is paid out of
     * @param traderWalletId the wallet that is credited
     * @param created false when the key had been used before and this is the earlier deposit
     */
    public record Prepared(Deposit deposit, PaymentRequest request, String agentWalletId, String traderWalletId,
            boolean created) {}

    private final CouncilRepository councils;
    private final StallRepository stalls;
    private final AgentRepository agents;
    private final WalletRegistry wallets;
    private final DepositRepository deposits;
    private final PaymentRequestRepository requests;
    private final Clock clock;
    private final Random random;
    private final long maxAmountNgwee;

    public RequestDeposit(CouncilRepository councils, StallRepository stalls, AgentRepository agents,
            WalletRegistry wallets, DepositRepository deposits, PaymentRequestRepository requests, Clock clock,
            Random random, long maxAmountNgwee) {
        this.councils = Objects.requireNonNull(councils);
        this.stalls = Objects.requireNonNull(stalls);
        this.agents = Objects.requireNonNull(agents);
        this.wallets = Objects.requireNonNull(wallets);
        this.deposits = Objects.requireNonNull(deposits);
        this.requests = Objects.requireNonNull(requests);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
        if (maxAmountNgwee <= 0) {
            throw new IllegalArgumentException("maxAmountNgwee must be positive");
        }
        this.maxAmountNgwee = maxAmountNgwee;
    }

    /**
     * @throws InvalidInputException for a bad amount, an inactive agent, a stall nobody holds, a missing wallet, or
     *     a missing key
     * @throws NotFoundException if the agent or stall is not in the council
     * @throws ConflictException if the same key was used at the same moment by another request (try again)
     */
    public Prepared execute(UUID councilId, UUID agentId, UUID stallId, long amountNgwee, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new InvalidInputException("an Idempotency-Key of 1 to 100 characters is required");
        }
        Council council = councils.find(councilId).orElseThrow(() -> new NotFoundException("no such council"));
        Agent agent = agents.find(councilId, agentId)
                .orElseThrow(() -> new NotFoundException("no agent " + agentId + " in this council"));

        Optional<Deposit> earlier = deposits.findByKey(councilId, agentId, idempotencyKey);
        if (earlier.isPresent()) {
            return prepared(earlier.get(), false);
        }

        if (amountNgwee <= 0) {
            throw new InvalidInputException("the amount must be greater than zero");
        }
        if (amountNgwee > maxAmountNgwee) {
            throw new InvalidInputException("the amount is more than the limit of " + maxAmountNgwee + " ngwee for one deposit");
        }
        if (!agent.active()) {
            throw new InvalidInputException("this agent is not active");
        }
        Stall stall = stalls.find(councilId, stallId)
                .orElseThrow(() -> new NotFoundException("no stall " + stallId + " in this council"));
        if (stall.status() != StallStatus.ALLOCATED) {
            throw new InvalidInputException("stall " + stall.code() + " has no trader, so it cannot take a deposit");
        }
        WalletOwner agentWallet = wallets.ofAgent(councilId, agentId)
                .orElseThrow(() -> new InvalidInputException("this agent has no payments wallet registered"));
        WalletOwner traderWallet = wallets.ofStall(councilId, stallId, WalletOwner.Role.TRADER)
                .orElseThrow(() -> new InvalidInputException("stall " + stall.code() + " has no trader wallet registered"));

        Instant now = clock.instant();
        Money amount = Money.ofMinor(amountNgwee, Currency.ZMW);
        PaymentRequest request = new PaymentRequest(UUID.randomUUID(), councilId, PaymentRequest.Purpose.DEPOSIT, stallId,
                amount, PaymentRequest.Status.SENT, null, null, now, null);
        requests.add(request);

        for (int attempt = 0; attempt < REFERENCE_ATTEMPTS; attempt++) {
            Deposit deposit = new Deposit(UUID.randomUUID(), councilId, stallId, agentId, amount, DepositStatus.PENDING,
                    DepositReference.generate(random), now.atZone(council.timezone()).toLocalDate(), request.id(), null,
                    null, idempotencyKey, now, now);
            if (deposits.add(deposit)) {
                return new Prepared(deposit, request, agentWallet.walletId(), traderWallet.walletId(), true);
            }
            if (deposits.findByKey(councilId, agentId, idempotencyKey).isPresent()) {
                // Someone using the same key got in between our check and our insert. Rolling back this attempt
                // (the exception) leaves nothing behind, and the caller's retry finds the winner.
                throw new ConflictException("that Idempotency-Key is being used by another request right now; try again");
            }
        }
        throw new ConflictException("could not find a free deposit reference; try again");
    }

    /** Rebuilds the wallets for a deposit that already exists, so a repeat can send its request again if needed. */
    private Prepared prepared(Deposit deposit, boolean created) {
        PaymentRequest request = requests.find(deposit.councilId(), deposit.paymentRequestId())
                .orElseThrow(() -> new IllegalStateException("deposit " + deposit.id() + " has no payment request"));
        String agentWallet = wallets.ofAgent(deposit.councilId(), deposit.agentId()).map(WalletOwner::walletId).orElse(null);
        String traderWallet = wallets.ofStall(deposit.councilId(), deposit.stallId(), WalletOwner.Role.TRADER)
                .map(WalletOwner::walletId).orElse(null);
        return new Prepared(deposit, request, agentWallet, traderWallet, created);
    }
}
