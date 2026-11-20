package zm.msika.core.stall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.common.Tokens;
import zm.msika.core.market.Market;
import zm.msika.core.market.MarketRepository;

/**
 * Use case: a council admin uploads a CSV to create a market's stalls in bulk.
 *
 * <p>All or nothing. If any row is bad, or any stall code already exists, no stall is created. Every
 * stall gets its own random QR token.
 */
public final class ImportStalls {

    private final MarketRepository markets;
    private final StallRepository stalls;
    private final Supplier<String> tokens;

    public ImportStalls(MarketRepository markets, StallRepository stalls) {
        this(markets, stalls, Tokens::newToken);
    }

    /** For tests, which want predictable tokens. */
    public ImportStalls(MarketRepository markets, StallRepository stalls, Supplier<String> tokens) {
        this.markets = Objects.requireNonNull(markets);
        this.stalls = Objects.requireNonNull(stalls);
        this.tokens = Objects.requireNonNull(tokens);
    }

    /**
     * @return the stalls created, in file order
     * @throws NotFoundException if the market is not in this council
     * @throws InvalidInputException if the file or any row is bad (it lists the row numbers)
     * @throws ConflictException if any of the stall codes already exists
     */
    public List<Stall> execute(UUID councilId, UUID marketId, String csv) {
        Market market = markets.find(councilId, marketId)
                .orElseThrow(() -> new NotFoundException("market not found"));

        List<StallCsv.Row> rows = StallCsv.parse(csv);

        List<String> codes = rows.stream()
                .map(r -> Stall.codeOf(market.code(), r.section(), r.number()))
                .toList();
        Set<String> taken = stalls.existingCodes(councilId, codes);
        if (!taken.isEmpty()) {
            List<String> shown = taken.stream().sorted().limit(10).toList();
            throw new ConflictException("these stall codes already exist, nothing was created: "
                    + String.join(", ", shown) + (taken.size() > shown.size() ? ", ..." : ""));
        }

        List<Stall> created = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            created.add(new Stall(
                    UUID.randomUUID(),
                    councilId,
                    marketId,
                    codes.get(i),
                    rows.get(i).stallClass(),
                    StallStatus.VACANT,
                    tokens.get()));
        }
        stalls.addAll(created);
        return created;
    }
}
