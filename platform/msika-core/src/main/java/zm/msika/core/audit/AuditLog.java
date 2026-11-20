package zm.msika.core.audit;

import java.util.UUID;

/**
 * Records who changed what. Every change a council user makes writes one row (ARCHITECTURE.md
 * section 9). Rows are only ever added: the database refuses to update or delete them.
 * Implemented in msika-api.
 */
public interface AuditLog {

    /**
     * @param councilId the council the change was made in
     * @param actor who made it (the signed-in user's id)
     * @param action what was done, like {@code market.create}
     * @param target what it was done to (an id or code)
     */
    void record(UUID councilId, String actor, String action, String target);
}
