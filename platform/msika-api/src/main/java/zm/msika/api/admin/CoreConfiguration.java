package zm.msika.api.admin;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.council.CreateCouncil;
import zm.msika.core.market.CreateMarket;
import zm.msika.core.market.MarketRepository;
import zm.msika.core.stall.AllocateStall;
import zm.msika.core.stall.FreeStall;
import zm.msika.core.stall.ImportStalls;
import zm.msika.core.stall.StallAllocationRepository;
import zm.msika.core.stall.StallRepository;
import zm.msika.core.stall.TraderDirectory;

/**
 * Creates the use cases from msika-core. msika-core knows nothing about Spring, so this is where its
 * plain classes are handed their repositories and turned into beans.
 */
@Configuration
class CoreConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CreateCouncil createCouncil(CouncilRepository councils, Clock clock) {
        return new CreateCouncil(councils, clock);
    }

    @Bean
    CreateMarket createMarket(CouncilRepository councils, MarketRepository markets, Clock clock) {
        return new CreateMarket(councils, markets, clock);
    }

    @Bean
    ImportStalls importStalls(MarketRepository markets, StallRepository stalls) {
        return new ImportStalls(markets, stalls);
    }

    @Bean
    AllocateStall allocateStall(
            StallRepository stalls, StallAllocationRepository allocations, TraderDirectory traders, Clock clock) {
        return new AllocateStall(stalls, allocations, traders, clock);
    }

    @Bean
    FreeStall freeStall(StallRepository stalls, StallAllocationRepository allocations, Clock clock) {
        return new FreeStall(stalls, allocations, clock);
    }
}
