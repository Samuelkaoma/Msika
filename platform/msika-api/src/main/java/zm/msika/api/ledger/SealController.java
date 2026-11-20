package zm.msika.api.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.security.MsikaPrincipal;
import zm.msika.api.seal.SealKeys;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.ledger.SealedDay;
import zm.msika.ledger.DailySeal;
import zm.msika.ledger.EcdsaSignatureScheme;

/**
 * Daily seals: read one, get the public key that checks them, and (for Msika operators) close days by
 * hand. Checking a seal against the ledger itself is done by {@code scripts/verify-seal}.
 */
@RestController
class SealController {

    private final LedgerService ledger;
    private final SealKeys keys;

    SealController(LedgerService ledger, SealKeys keys) {
        this.ledger = ledger;
        this.keys = keys;
    }

    /** A sealed day. Hashes are hexadecimal and the signature is Base64, so anyone can check them. */
    record SealResponse(
            UUID councilId,
            LocalDate businessDate,
            int entryCount,
            String merkleRoot,
            String previousSealHash,
            String sealHash,
            String algorithm,
            String signature,
            String keyId,
            Instant sealedAt) {

        static SealResponse of(SealedDay sealed) {
            DailySeal seal = sealed.seal();
            HexFormat hex = HexFormat.of();
            return new SealResponse(
                    seal.councilId(),
                    seal.businessDate(),
                    seal.entryCount(),
                    hex.formatHex(seal.merkleRoot()),
                    hex.formatHex(seal.previousSealHash()),
                    hex.formatHex(seal.sealHash()),
                    seal.algorithm(),
                    Base64.getEncoder().encodeToString(seal.signature()),
                    sealed.keyId(),
                    sealed.sealedAt());
        }
    }

    record SealKeyResponse(String keyId, String algorithm, String publicKey) {}

    record DayCloseRequest(LocalDate through) {}

    record DayCloseResponse(List<LocalDate> sealed) {}

    /** The public key that checks every seal. Public on purpose: a key that only we could read would prove nothing. */
    @GetMapping("/api/v1/seal-key")
    SealKeyResponse sealKey() {
        return new SealKeyResponse(keys.keyId(), new EcdsaSignatureScheme().algorithm(), keys.publicKeyBase64());
    }

    /**
     * The signed-in user's own council's seal for a day. The security rules allow only council admins and
     * officers here (so the user always has a council); an operator uses the council path below.
     */
    @GetMapping("/api/v1/seals/{date}")
    SealResponse mySeal(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        MsikaPrincipal user = CurrentUser.require();
        return SealResponse.of(ledger.requireSeal(user.councilId(), date));
    }

    @GetMapping("/api/v1/councils/{councilId}/seals/{date}")
    SealResponse seal(
            @PathVariable UUID councilId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return SealResponse.of(ledger.requireSeal(councilId, date));
    }

    /**
     * Operator only. Seals every unsealed day up to and including {@code through}. Safe to call twice: a
     * day already sealed is left alone. Answers with the days it sealed (none, if there was nothing to do).
     */
    @PostMapping("/api/v1/councils/{councilId}/day-close")
    DayCloseResponse closeDays(@PathVariable UUID councilId, @RequestBody DayCloseRequest request) {
        if (request.through() == null) {
            throw new InvalidInputException("through is required, like 2026-11-24");
        }
        List<SealedDay> sealed = ledger.closeDays(councilId, request.through(), CurrentUser.actor());
        return new DayCloseResponse(sealed.stream().map(s -> s.seal().businessDate()).toList());
    }
}
