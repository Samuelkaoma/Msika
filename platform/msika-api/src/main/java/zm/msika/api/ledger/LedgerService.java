package zm.msika.api.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.ledger.DayClose;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.ledger.SealRepository;
import zm.msika.core.ledger.SealedDay;

/**
 * Runs the ledger use cases in a database transaction as one council. This is the entry point other code
 * uses to record an entry (the payment callbacks, SMS and deposits in later cards) and to close days.
 *
 * <p>Recording is not audited here: the ledger itself is the record. Closing days is, because a person
 * may do it by hand.
 */
@Service
@Transactional
public class LedgerService {

    private final RecordEntry recordEntry;
    private final DayClose dayClose;
    private final SealRepository seals;
    private final TenantContext tenant;
    private final AuditLog audit;

    LedgerService(
            RecordEntry recordEntry, DayClose dayClose, SealRepository seals, TenantContext tenant, AuditLog audit) {
        this.recordEntry = recordEntry;
        this.dayClose = dayClose;
        this.seals = seals;
        this.tenant = tenant;
        this.audit = audit;
    }

    /** Records an entry, or returns the one already recorded under the same key. */
    public RecordEntry.Recorded record(NewEntry request) {
        tenant.use(request.councilId());
        return recordEntry.execute(request);
    }

    /**
     * Seals every unsealed day up to and including {@code through}.
     *
     * @param actor who asked: a user id, or {@code system:day-close} for the scheduled job
     * @return the days sealed by this call, oldest first (empty if there was nothing to do)
     */
    public List<SealedDay> closeDays(UUID councilId, LocalDate through, String actor) {
        tenant.use(councilId);
        List<SealedDay> sealed = dayClose.execute(councilId, through);
        if (!sealed.isEmpty()) {
            String first = sealed.get(0).seal().businessDate().toString();
            String last = sealed.get(sealed.size() - 1).seal().businessDate().toString();
            audit.record(councilId, actor, "day.close", first.equals(last) ? first : first + " to " + last);
        }
        return sealed;
    }

    @Transactional(readOnly = true)
    public Optional<SealedDay> seal(UUID councilId, LocalDate businessDate) {
        tenant.use(councilId);
        return seals.find(councilId, businessDate);
    }

    @Transactional(readOnly = true)
    public SealedDay requireSeal(UUID councilId, LocalDate businessDate) {
        return seal(councilId, businessDate)
                .orElseThrow(() -> new NotFoundException("no seal for " + businessDate + " (the day is not closed yet)"));
    }
}
