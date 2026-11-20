package zm.msika.core.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where daily seals are kept. Implemented in msika-api. Seals are only added, never changed. */
public interface SealRepository {

    void add(UUID councilId, SealedDay sealed);

    Optional<SealedDay> find(UUID councilId, LocalDate businessDate);

    /** The most recent seal, if the council has any. */
    Optional<SealedDay> latest(UUID councilId);

    /** All of a council's seals, oldest first. */
    List<SealedDay> list(UUID councilId);
}
