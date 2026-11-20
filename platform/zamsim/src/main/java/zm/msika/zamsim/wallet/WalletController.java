package zm.msika.zamsim.wallet;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.zamsim.wallet.Wallets.StatementLine;
import zm.msika.zamsim.wallet.Wallets.Transaction;
import zm.msika.zamsim.wallet.Wallets.Wallet;
import zm.msika.zamsim.wallet.Wallets.WalletType;

/**
 * The simulated Zamtel Money API, shaped like a typical mobile money API so the real one can replace it
 * later by swapping an adapter (ARCHITECTURE.md section 6).
 *
 * <p>Every write needs an {@code Idempotency-Key} header: the same key returns the first result. Writes
 * answer {@code 202 Accepted} with the transaction, whose {@code status} is {@code SUCCEEDED} or
 * {@code FAILED}. A payment that cannot be made (not enough money) is a FAILED transaction, not an HTTP
 * error: the request was understood and recorded. Amounts are whole ngwee.
 *
 * <p>There is no sign-in: zamsim is a simulator that only ever runs on a private network next to Msika.
 */
@RestController
class WalletController {

    private final WalletService service;

    WalletController(WalletService service) {
        this.service = service;
    }

    record CreateWalletRequest(WalletType type, String name, String msisdn) {}

    record CashInRequest(UUID agentWalletId, UUID customerWalletId, long amountNgwee, String reference) {}

    record TransferRequest(UUID fromWalletId, UUID toWalletId, long amountNgwee, String reference) {}

    record AdminMoveRequest(UUID walletId, long amountNgwee, String reference) {}

    record WalletResponse(UUID id, WalletType type, String name, String msisdn, long balanceNgwee, Instant createdAt) {
        static WalletResponse of(Wallet w) {
            return new WalletResponse(w.id(), w.type(), w.name(), w.msisdn(), w.balanceNgwee(), w.createdAt());
        }
    }

    @PostMapping("/v1/wallets")
    ResponseEntity<WalletResponse> createWallet(@RequestBody CreateWalletRequest request) {
        Wallet wallet = service.createWallet(request.type(), request.name(), request.msisdn());
        return ResponseEntity.created(URI.create("/v1/wallets/" + wallet.id())).body(WalletResponse.of(wallet));
    }

    @GetMapping("/v1/wallets/{id}")
    WalletResponse wallet(@PathVariable UUID id) {
        return WalletResponse.of(service.wallet(id));
    }

    @PostMapping("/v1/cash-in")
    ResponseEntity<Transaction> cashIn(
            @RequestHeader("Idempotency-Key") String key, @RequestBody CashInRequest request) {
        return accepted(service.cashIn(key, request.agentWalletId(), request.customerWalletId(),
                request.amountNgwee(), request.reference()));
    }

    @PostMapping("/v1/transfers")
    ResponseEntity<Transaction> transfer(
            @RequestHeader("Idempotency-Key") String key, @RequestBody TransferRequest request) {
        return accepted(service.transfer(key, request.fromWalletId(), request.toWalletId(),
                request.amountNgwee(), request.reference()));
    }

    /** Money enters the system, like an agent buying float. For setting up demos and tests. */
    @PostMapping("/v1/admin/fund")
    ResponseEntity<Transaction> fund(
            @RequestHeader("Idempotency-Key") String key, @RequestBody AdminMoveRequest request) {
        return accepted(service.fund(key, request.walletId(), request.amountNgwee(), request.reference()));
    }

    /** Money leaves the system. */
    @PostMapping("/v1/admin/withdraw")
    ResponseEntity<Transaction> withdraw(
            @RequestHeader("Idempotency-Key") String key, @RequestBody AdminMoveRequest request) {
        return accepted(service.withdraw(key, request.walletId(), request.amountNgwee(), request.reference()));
    }

    record RequestToPayRequest(UUID merchantWalletId, UUID customerWalletId, long amountNgwee, String reference,
            Integer ttlSeconds) {}

    /**
     * A merchant asks a customer to pay. Answers 202 with a PENDING transaction: nothing has moved. The
     * customer approves or rejects it (in the sim console), and the result arrives by callback.
     */
    @PostMapping("/v1/request-to-pay")
    ResponseEntity<Transaction> requestToPay(
            @RequestHeader("Idempotency-Key") String key, @RequestBody RequestToPayRequest request) {
        return accepted(service.requestToPay(key, request.merchantWalletId(), request.customerWalletId(),
                request.amountNgwee(), request.reference(), request.ttlSeconds()));
    }

    /** The requests waiting for this customer, for the console. */
    @GetMapping("/v1/request-to-pay")
    List<Transaction> pendingRequests(@RequestParam("customer") UUID customer) {
        return service.pendingRequests(customer);
    }

    /** The customer approves: the payment is made now, or fails for lack of money. Safe to repeat. */
    @PostMapping("/v1/request-to-pay/{id}/approve")
    Transaction approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    /** The customer turns the request down. Safe to repeat. */
    @PostMapping("/v1/request-to-pay/{id}/reject")
    Transaction reject(@PathVariable UUID id) {
        return service.reject(id);
    }

    @GetMapping("/v1/transactions/{id}")
    Transaction transaction(@PathVariable UUID id) {
        return service.transaction(id);
    }

    /** A wallet's ledger lines, oldest first. Dates are Africa/Lusaka days, inclusive. Used by reconciliation. */
    @GetMapping("/v1/statements")
    List<StatementLine> statement(
            @RequestParam("wallet") UUID wallet,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "limit", defaultValue = "1000") int limit) {
        return service.statement(wallet, from, to, limit);
    }

    private static ResponseEntity<Transaction> accepted(Transaction transaction) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(transaction);
    }
}
