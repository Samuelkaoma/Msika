package zm.msika.core.trader;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.phone.Language;
import zm.msika.core.phone.PhoneNumber;

/**
 * Use case: a council registers a trader.
 *
 * <p>The number is normalised first, so {@code 0977 123 739}, {@code 260977123739} and
 * {@code +260977123739} are the same trader. Registering the same trader twice is a conflict and
 * creates nothing.
 */
public final class RegisterTrader {

    private final CouncilRepository councils;
    private final TraderRepository traders;
    private final Clock clock;

    public RegisterTrader(CouncilRepository councils, TraderRepository traders, Clock clock) {
        this.councils = Objects.requireNonNull(councils);
        this.traders = Objects.requireNonNull(traders);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param phone the number in any usual written form
     * @param language {@code en}, {@code ny} or {@code bem}; blank means English
     * @throws NotFoundException if the council does not exist
     * @throws ConflictException if a trader with this number is already registered in the council
     */
    public Trader execute(UUID councilId, String phone, String displayName, String language) {
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        PhoneNumber number = PhoneNumber.parse(phone);
        Trader trader = new Trader(
                UUID.randomUUID(), councilId, number, displayName == null ? "" : displayName,
                Language.fromCode(language), null, null, clock.instant());
        if (traders.existsByPhone(councilId, number)) {
            throw new ConflictException("a trader with this phone number is already registered");
        }
        traders.add(trader);
        return trader;
    }
}
