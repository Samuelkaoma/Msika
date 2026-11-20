package zm.msika.core.trader;

import java.util.List;
import java.util.UUID;
import zm.msika.core.phone.PhoneNumber;

/** Where traders are kept. Implemented in msika-api. */
public interface TraderRepository {

    void add(Trader trader);

    List<Trader> list(UUID councilId);

    boolean existsByPhone(UUID councilId, PhoneNumber phone);
}
