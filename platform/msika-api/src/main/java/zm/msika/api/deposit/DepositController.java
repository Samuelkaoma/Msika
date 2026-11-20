package zm.msika.api.deposit;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.deposit.DepositStatus;
import zm.msika.core.wallet.WalletOwner;

/**
 * Deposits and the wallets they use. Council users call these for now; the SMS commands of M-19 will call the
 * same services. Writing needs a role that can write (readers get 403 from the council rule).
 */
@RestController
class DepositController {

    record StartDepositRequest(UUID agentId, UUID stallId, long amountNgwee) {}

    record DisputeRequest(String reason) {}

    record RegisterWalletRequest(String walletId, String role, UUID stallId, UUID agentId) {}

    record DepositResponse(UUID id, UUID councilId, UUID stallId, UUID agentId, long amountNgwee, DepositStatus status,
            String reference, LocalDate businessDate, UUID paymentRequestId, Long ledgerEntryId, String statusReason,
            Instant createdAt, Instant updatedAt) {
        static DepositResponse of(Deposit d) {
            return new DepositResponse(d.id(), d.councilId(), d.stallId(), d.agentId(), d.amount().minorUnits(),
                    d.status(), d.reference(), d.businessDate(), d.paymentRequestId(), d.ledgerEntryId(),
                    d.statusReason(), d.createdAt(), d.updatedAt());
        }
    }

    record WalletResponse(String walletId, UUID councilId, String role, UUID stallId, UUID agentId) {
        static WalletResponse of(WalletOwner w) {
            return new WalletResponse(w.walletId(), w.councilId(), w.role().name(), w.stallId(), w.agentId());
        }
    }

    private final DepositService service;

    DepositController(DepositService service) {
        this.service = service;
    }

    /** 202 for a new deposit (the payment is still being made), 200 when the Idempotency-Key was used before. */
    @PostMapping("/api/v1/councils/{councilId}/deposits")
    ResponseEntity<DepositResponse> start(@PathVariable UUID councilId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody StartDepositRequest request) {
        if (request.agentId() == null || request.stallId() == null) {
            throw new InvalidInputException("agentId and stallId are required");
        }
        DepositService.Started started = service.start(councilId, request.agentId(), request.stallId(),
                request.amountNgwee(), key);
        DepositResponse body = DepositResponse.of(started.deposit());
        URI location = URI.create("/api/v1/councils/" + councilId + "/deposits/" + body.id());
        return started.created() ? ResponseEntity.accepted().location(location).body(body)
                : ResponseEntity.ok().location(location).body(body);
    }

    @GetMapping("/api/v1/councils/{councilId}/deposits/{id}")
    DepositResponse get(@PathVariable UUID councilId, @PathVariable UUID id) {
        return DepositResponse.of(service.get(councilId, id));
    }

    @GetMapping("/api/v1/councils/{councilId}/deposits")
    List<DepositResponse> list(@PathVariable UUID councilId, @RequestParam(required = false) String status) {
        DepositStatus parsed = null;
        if (status != null) {
            try {
                parsed = DepositStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("status must be one of PENDING, CONFIRMED, FAILED, CANCELLED, DISPUTED");
            }
        }
        return service.list(councilId, parsed).stream().map(DepositResponse::of).toList();
    }

    @PostMapping("/api/v1/councils/{councilId}/deposits/{id}/cancel")
    DepositResponse cancel(@PathVariable UUID councilId, @PathVariable UUID id) {
        return DepositResponse.of(service.cancel(councilId, id));
    }

    @PostMapping("/api/v1/councils/{councilId}/deposits/{id}/dispute")
    DepositResponse dispute(@PathVariable UUID councilId, @PathVariable UUID id,
            @RequestBody(required = false) DisputeRequest request) {
        return DepositResponse.of(service.dispute(councilId, id, request == null ? null : request.reason()));
    }

    @PostMapping("/api/v1/councils/{councilId}/wallets")
    ResponseEntity<WalletResponse> registerWallet(@PathVariable UUID councilId, @RequestBody RegisterWalletRequest request) {
        WalletOwner.Role role;
        try {
            role = WalletOwner.Role.valueOf(request.role());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidInputException("role must be one of TRADER, MERCHANT, AGENT, SUPPLIER");
        }
        WalletOwner w = service.registerWallet(councilId, request.walletId(), role, request.stallId(), request.agentId());
        return ResponseEntity.created(URI.create("/api/v1/councils/" + councilId + "/wallets")).body(WalletResponse.of(w));
    }

    @GetMapping("/api/v1/councils/{councilId}/wallets")
    List<WalletResponse> wallets(@PathVariable UUID councilId) {
        return service.wallets(councilId).stream().map(WalletResponse::of).toList();
    }
}
