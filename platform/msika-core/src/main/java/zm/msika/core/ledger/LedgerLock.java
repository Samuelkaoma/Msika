package zm.msika.core.ledger;

import java.util.UUID;

/**
 * Keeps day close and recording from interfering. Implemented in msika-api with Postgres advisory locks,
 * which last until the transaction ends.
 *
 * <p>Recording takes a <em>shared</em> lock, so many entries can be recorded at once. Closing a day takes
 * an <em>exclusive</em> lock, so it waits for every entry being recorded to finish, and while it runs no
 * new entry starts. When it commits, later entries see the new seal and go to the next day.
 */
public interface LedgerLock {

    /** Waits only for a day close in progress. Released when the transaction ends. */
    void lockForRecording(UUID councilId);

    /** Waits for every recording and other close to finish. Released when the transaction ends. */
    void lockForClosing(UUID councilId);
}
