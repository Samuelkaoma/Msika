package zm.msika.core.council;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where councils are kept. Implemented in msika-api. */
public interface CouncilRepository {

    void add(Council council);

    Optional<Council> find(UUID id);

    /** The ids of all councils, oldest first. Used by jobs that run once per council. */
    List<UUID> listIds();
}
