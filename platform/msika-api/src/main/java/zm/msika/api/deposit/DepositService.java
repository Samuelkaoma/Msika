package zm.msika.api.deposit;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import zm.msika.api.payments.PaymentService;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.deposit.ChangeDeposit;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.deposit.DepositRepository;
import zm.msika.core.deposit.DepositStatus;
import zm.msika.core.deposit.RequestDeposit;
import zm.msika.core.payments.PaymentsWallet;
import zm.msika.core.wallet.RegisterWallet;
import zm.msika.core.wallet.VerifyWalletOwnership;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;

/**
 * Runs the deposit use cases. Starting a deposit is two database transactions with the call to the payments
 * system between them (never inside one): save the deposit and its request, then send, then remember the
 * payments system's id. If the send fails the deposit stays PENDING and sending the same command again retries it.
 */
@Service
class DepositService {

    /** @param created false when the Idempotency-Key had been used before */
    record Started(Deposit deposit, boolean created) {}

    private final RequestDeposit requestDeposit;
    private final ChangeDeposit changeDeposit;
    private final RegisterWallet registerWallet;
    private final VerifyWalletOwnership verifyOwnership;
    private final DepositRepository deposits;
    private final WalletRegistry wallets;
    private final PaymentService payments;
    private final TenantContext tenant;
    private final AuditLog audit;

    DepositService(RequestDeposit requestDeposit, ChangeDeposit changeDeposit, RegisterWallet registerWallet,
            VerifyWalletOwnership verifyOwnership, DepositRepository deposits, WalletRegistry wallets,
            PaymentService payments, TenantContext tenant, AuditLog audit) {
        this.requestDeposit = requestDeposit;
        this.changeDeposit = changeDeposit;
        this.registerWallet = registerWallet;
        this.verifyOwnership = verifyOwnership;
        this.deposits = deposits;
        this.wallets = wallets;
        this.payments = payments;
        this.tenant = tenant;
        this.audit = audit;
    }

    /**
     * @throws zm.msika.core.payments.PaymentsUnavailableException if the payments system could not be asked; the
     *     deposit is saved and the same command can be sent again
     */
    Started start(UUID councilId, UUID agentId, UUID stallId, long amountNgwee, String idempotencyKey) {
        RequestDeposit.Prepared prepared = tenant.inCouncil(councilId, () -> {
            RequestDeposit.Prepared p = requestDeposit.execute(councilId, agentId, stallId, amountNgwee, idempotencyKey);
            if (p.created()) {
                audit.record(councilId, CurrentUser.actor(), "deposit.start", p.deposit().id().toString());
            }
            return p;
        });
        boolean notSentYet = !prepared.request().isFinal() && prepared.request().paymentsTransactionId() == null;
        if (notSentYet && prepared.agentWalletId() != null && prepared.traderWalletId() != null) {
            payments.send(prepared.request(), prepared.agentWalletId(), prepared.traderWalletId());
        }
        return new Started(get(councilId, prepared.deposit().id()), prepared.created());
    }

    Deposit cancel(UUID councilId, UUID depositId) {
        return tenant.inCouncil(councilId, () -> {
            Deposit d = changeDeposit.cancel(councilId, depositId);
            audit.record(councilId, CurrentUser.actor(), "deposit.cancel", depositId.toString());
            return d;
        });
    }

    Deposit dispute(UUID councilId, UUID depositId, String reason) {
        return tenant.inCouncil(councilId, () -> {
            Deposit d = changeDeposit.dispute(councilId, depositId, reason);
            audit.record(councilId, CurrentUser.actor(), "deposit.dispute", depositId.toString());
            return d;
        });
    }

    Deposit get(UUID councilId, UUID depositId) {
        return tenant.inCouncil(councilId, () -> deposits.find(councilId, depositId)
                .orElseThrow(() -> new NotFoundException("no deposit " + depositId)));
    }

    List<Deposit> list(UUID councilId, DepositStatus status) {
        return tenant.inCouncil(councilId, () -> deposits.list(councilId, status, 200));
    }

    /**
     * Registers a wallet only if the payments system agrees it is that kind of wallet and, for trader and agent
     * wallets, that it is registered to that person's phone. The payments system is asked first, outside any
     * database transaction. The check runs after the row is written, in the same transaction, so the usual answers
     * (a duplicate, a bad shape, an unknown stall) come first, and a failed check rolls the row back: nothing that
     * fails the check is ever stored.
     */
    WalletOwner registerWallet(UUID councilId, String walletId, WalletOwner.Role role, UUID stallId, UUID agentId) {
        String id = walletId == null ? null : walletId.trim();
        PaymentsWallet inPayments = id == null || id.isEmpty() ? null : payments.findWallet(id).orElse(null);
        return tenant.inCouncil(councilId, () -> {
            WalletOwner w = registerWallet.execute(councilId, walletId, role, stallId, agentId);
            verifyOwnership.check(councilId, w.walletId(), role, stallId, agentId, inPayments);
            audit.record(councilId, CurrentUser.actor(), "wallet.register", role + " " + stallId + " " + agentId);
            return w;
        });
    }

    List<WalletOwner> wallets(UUID councilId) {
        return tenant.inCouncil(councilId, () -> wallets.list(councilId));
    }
}
