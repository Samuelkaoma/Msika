package zm.msika.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import zm.msika.core.common.ConflictException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.market.Market;
import zm.msika.core.market.MarketRepository;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallAllocation;
import zm.msika.core.stall.StallAllocationRepository;
import zm.msika.core.stall.StallRepository;
import zm.msika.core.stall.StallStatus;
import zm.msika.core.stall.TraderDirectory;

/** In-memory stand-ins for the repositories, so use cases can be tested without a database. */
public final class Fakes {

    private Fakes() {}

    public static final class Councils implements CouncilRepository {
        public final Map<UUID, Council> byId = new HashMap<>();

        @Override
        public void add(Council council) {
            byId.put(council.id(), council);
        }

        @Override
        public List<UUID> listIds() {
            return byId.values().stream().sorted(java.util.Comparator.comparing(Council::createdAt)).map(Council::id).toList();
        }

        @Override
        public Optional<Council> find(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }
    }

    public static final class Markets implements MarketRepository {
        public final Map<UUID, Market> byId = new HashMap<>();

        @Override
        public void add(Market market) {
            byId.put(market.id(), market);
        }

        @Override
        public Optional<Market> find(UUID councilId, UUID marketId) {
            return Optional.ofNullable(byId.get(marketId)).filter(m -> m.councilId().equals(councilId));
        }

        @Override
        public List<Market> list(UUID councilId) {
            return byId.values().stream().filter(m -> m.councilId().equals(councilId)).toList();
        }

        @Override
        public boolean existsByCode(UUID councilId, String code) {
            return list(councilId).stream().anyMatch(m -> m.code().equals(code));
        }
    }

    public static final class Stalls implements StallRepository {
        public final Map<UUID, Stall> byId = new HashMap<>();
        public int addAllCalls;

        @Override
        public void addAll(List<Stall> stalls) {
            addAllCalls++;
            stalls.forEach(s -> byId.put(s.id(), s));
        }

        @Override
        public Optional<Stall> find(UUID councilId, UUID stallId) {
            return Optional.ofNullable(byId.get(stallId)).filter(s -> s.councilId().equals(councilId));
        }

        @Override
        public List<Stall> listByMarket(UUID councilId, UUID marketId) {
            return byId.values().stream()
                    .filter(s -> s.councilId().equals(councilId) && s.marketId().equals(marketId))
                    .toList();
        }

        @Override
        public Set<String> existingCodes(UUID councilId, Collection<String> codes) {
            Set<String> found = new HashSet<>();
            for (Stall s : byId.values()) {
                if (s.councilId().equals(councilId) && codes.contains(s.code())) {
                    found.add(s.code());
                }
            }
            return found;
        }

        @Override
        public void setStatus(UUID councilId, UUID stallId, StallStatus status) {
            byId.computeIfPresent(stallId, (id, s) -> s.withStatus(status));
        }
    }

    public static final class Allocations implements StallAllocationRepository {
        public final List<StallAllocation> all = new ArrayList<>();

        @Override
        public void add(StallAllocation allocation) {
            if (findActive(allocation.councilId(), allocation.stallId()).isPresent()) {
                throw new ConflictException("stall already has an active allocation");
            }
            all.add(allocation);
        }

        @Override
        public Optional<StallAllocation> findActive(UUID councilId, UUID stallId) {
            return all.stream()
                    .filter(a -> a.councilId().equals(councilId) && a.stallId().equals(stallId) && a.isActive())
                    .findFirst();
        }

        @Override
        public void end(UUID councilId, UUID allocationId, Instant at) {
            for (int i = 0; i < all.size(); i++) {
                StallAllocation a = all.get(i);
                if (a.id().equals(allocationId)) {
                    all.set(i, new StallAllocation(a.id(), a.councilId(), a.stallId(), a.traderId(), a.validFrom(), at));
                }
            }
        }
    }

    public static final class Traders implements TraderDirectory {
        public final Set<String> known = new HashSet<>();

        public UUID add(UUID councilId) {
            UUID id = UUID.randomUUID();
            known.add(councilId + "/" + id);
            return id;
        }

        @Override
        public boolean exists(UUID councilId, UUID traderId) {
            return known.contains(councilId + "/" + traderId);
        }
    }
}
