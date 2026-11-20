package zm.msika.core.ledger;

import java.security.PrivateKey;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.ledger.DailySeal;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.SignatureScheme;

/**
 * Use case: close a council's trading days (ARCHITECTURE.md section 4.4).
 *
 * <p>Closing a day builds the Merkle tree over that day's entries in id order, signs the council, date,
 * entry count, root and the previous seal's hash, and stores the seal. From then on nothing can be added
 * to that day: new entries for it go to the next day.
 *
 * <p>Safe to run twice: a day that is already sealed is left alone and not sealed again. It also closes
 * any earlier day that was missed (for example because the job did not run), oldest first, so the chain of
 * seals never has a gap. A day with no entries is sealed too, with the root of an empty tree.
 */
public final class DayClose {

    private final CouncilRepository councils;
    private final LedgerRepository ledger;
    private final SealRepository seals;
    private final LedgerLock lock;
    private final SignatureScheme scheme;
    private final PrivateKey signingKey;
    private final String keyId;
    private final Clock clock;

    public DayClose(
            CouncilRepository councils,
            LedgerRepository ledger,
            SealRepository seals,
            LedgerLock lock,
            SignatureScheme scheme,
            PrivateKey signingKey,
            String keyId,
            Clock clock) {
        this.councils = Objects.requireNonNull(councils);
        this.ledger = Objects.requireNonNull(ledger);
        this.seals = Objects.requireNonNull(seals);
        this.lock = Objects.requireNonNull(lock);
        this.scheme = Objects.requireNonNull(scheme);
        this.signingKey = Objects.requireNonNull(signingKey);
        this.keyId = Objects.requireNonNull(keyId);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Seals every day not yet sealed, up to and including {@code through}.
     *
     * @return the days sealed by this call, oldest first; empty if there was nothing to do
     * @throws NotFoundException if the council does not exist
     * @throws InvalidInputException if {@code through} is a day that has not started yet in the council's
     *     time zone
     */
    public List<SealedDay> execute(UUID councilId, LocalDate through) {
        Council council = councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        LocalDate today = clock.instant().atZone(council.timezone()).toLocalDate();
        if (through.isAfter(today)) {
            throw new InvalidInputException("cannot close " + through + ": it has not started yet (today is " + today + ")");
        }

        lock.lockForClosing(councilId);

        Optional<SealedDay> latest = seals.latest(councilId);
        LocalDate next = latest.map(s -> s.seal().businessDate().plusDays(1))
                .orElseGet(() -> council.createdAt().atZone(council.timezone()).toLocalDate());

        List<SealedDay> sealedNow = new ArrayList<>();
        byte[] previousHash = latest.map(s -> s.seal().sealHash()).orElse(DailySeal.GENESIS_HASH);
        for (LocalDate day = next; !day.isAfter(through); day = day.plusDays(1)) {
            List<LedgerEntry> entries = ledger.entriesOfDay(councilId, day);
            DailySeal seal = DailySeal.seal(councilId, day, entries, previousHash, scheme, signingKey);
            SealedDay sealed = new SealedDay(seal, keyId, clock.instant());
            seals.add(councilId, sealed);
            sealedNow.add(sealed);
            previousHash = seal.sealHash();
        }
        return sealedNow;
    }
}
