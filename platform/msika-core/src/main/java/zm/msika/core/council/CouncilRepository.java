package zm.msika.core.council;

import java.util.Optional;
import java.util.UUID;

/** Where councils are kept. Implemented in msika-api. */
public interface CouncilRepository {

    void add(Council council);

    Optional<Council> find(UUID id);
}
