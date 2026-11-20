package zm.msika.core.stall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CreateCouncil;
import zm.msika.core.market.CreateMarket;
import zm.msika.core.market.Market;

/** Specification for the council, market and stall use cases, against in-memory repositories. */
class StallUseCasesTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);
    private final Fakes.Councils councils = new Fakes.Councils();
    private final Fakes.Markets markets = new Fakes.Markets();
    private final Fakes.Stalls stalls = new Fakes.Stalls();
    private final Fakes.Allocations allocations = new Fakes.Allocations();
    private final Fakes.Traders traders = new Fakes.Traders();

    private final CreateCouncil createCouncil = new CreateCouncil(councils, clock);
    private final CreateMarket createMarket = new CreateMarket(councils, markets, clock);
    private final ImportStalls importStalls = new ImportStalls(markets, stalls);
    private final AllocateStall allocate = new AllocateStall(stalls, allocations, traders, clock);
    private final FreeStall free = new FreeStall(stalls, allocations, clock);

    private Council council;
    private Market market;

    @BeforeEach
    void setUp() {
        council = createCouncil.execute("Lusaka City Council", null);
        market = createMarket.execute(council.id(), "sow", "Soweto Market", "Lusaka");
    }

    private Stall importOne(String section, String number) {
        return importStalls.execute(council.id(), market.id(),
                "section,number,class\n" + section + "," + number + ",STANDARD\n").get(0);
    }

    @Nested
    @DisplayName("councils and markets")
    class CouncilsAndMarkets {

        @Test
        @DisplayName("a council gets the Lusaka time zone by default")
        void defaultTimezone() {
            assertThat(council.timezone().getId()).isEqualTo("Africa/Lusaka");
        }

        @Test
        @DisplayName("an unknown time zone or a one-letter name is refused")
        void badCouncil() {
            assertThatThrownBy(() -> createCouncil.execute("Lusaka City Council", "Mars/Olympus"))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> createCouncil.execute("L", null)).isInstanceOf(InvalidInputException.class);
        }

        @Test
        @DisplayName("a market code is stored in capitals")
        void marketCodeUppercased() {
            assertThat(market.code()).isEqualTo("SOW");
            assertThat(market.dayCloseTime().toString()).isEqualTo("20:00");
        }

        @Test
        @DisplayName("the same market code twice in one council is a conflict")
        void duplicateMarketCode() {
            assertThatThrownBy(() -> createMarket.execute(council.id(), "SOW", "Another", ""))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("two councils may use the same market code")
        void sameCodeDifferentCouncil() {
            Council other = createCouncil.execute("Kitwe City Council", null);

            assertThat(createMarket.execute(other.id(), "SOW", "Soweto Kitwe", "").code()).isEqualTo("SOW");
        }

        @Test
        @DisplayName("a market in a council that does not exist is not found")
        void unknownCouncil() {
            assertThatThrownBy(() -> createMarket.execute(UUID.randomUUID(), "ABC", "Nowhere", ""))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("bad market codes and names are refused")
        void badMarket() {
            assertThatThrownBy(() -> createMarket.execute(council.id(), "S", "Soweto", ""))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> createMarket.execute(council.id(), "SOW1", "Soweto", ""))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> createMarket.execute(council.id(), "ABC", " ", ""))
                    .isInstanceOf(InvalidInputException.class);
        }
    }

    @Nested
    @DisplayName("importing stalls")
    class Importing {

        @Test
        @DisplayName("creates vacant stalls with codes like SOW-B-114")
        void createsStalls() {
            List<Stall> created = importStalls.execute(council.id(), market.id(),
                    "section,number,class\nB,114,STANDARD\nB,115,LARGE\n");

            assertThat(created).extracting(Stall::code).containsExactly("SOW-B-114", "SOW-B-115");
            assertThat(created).extracting(Stall::status).containsOnly(StallStatus.VACANT);
            assertThat(created).extracting(Stall::stallClass).containsExactly("STANDARD", "LARGE");
        }

        @Test
        @DisplayName("every stall gets its own random QR token")
        void uniqueTokens() {
            List<Stall> created = importStalls.execute(council.id(), market.id(),
                    "section,number,class\nB,1,STANDARD\nB,2,STANDARD\nB,3,STANDARD\n");

            Set<String> tokens = new HashSet<>(created.stream().map(Stall::qrToken).toList());
            assertThat(tokens).hasSize(3);
            assertThat(tokens).allSatisfy(t -> assertThat(t).hasSize(22).doesNotContain(" ", "SOW"));
        }

        @Test
        @DisplayName("a file with a bad row creates nothing, and names the row")
        void badRowCreatesNothing() {
            assertThatThrownBy(() -> importStalls.execute(council.id(), market.id(),
                    "section,number,class\nB,1,STANDARD\nB,oops,STANDARD\n"))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(t -> assertThat(((InvalidInputException) t).rows().get(0).row()).isEqualTo(3));

            assertThat(stalls.byId).isEmpty();
        }

        @Test
        @DisplayName("importing the same file twice is refused the second time and creates nothing more")
        void importTwice() {
            String csv = "section,number,class\nB,1,STANDARD\nB,2,STANDARD\n";
            importStalls.execute(council.id(), market.id(), csv);

            assertThatThrownBy(() -> importStalls.execute(council.id(), market.id(), csv))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("SOW-B-1");
            assertThat(stalls.byId).hasSize(2);
        }

        @Test
        @DisplayName("a market of another council is not found")
        void marketOfAnotherCouncil() {
            Council other = createCouncil.execute("Kitwe City Council", null);

            assertThatThrownBy(() -> importStalls.execute(other.id(), market.id(),
                    "section,number,class\nB,1,STANDARD\n")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("allocating and freeing a stall")
    class Allocation {

        @Test
        @DisplayName("a vacant stall can be allocated to a trader, and becomes ALLOCATED")
        void allocateVacantStall() {
            Stall stall = importOne("B", "114");
            UUID trader = traders.add(council.id());

            StallAllocation allocation = allocate.execute(council.id(), stall.id(), trader);

            assertThat(allocation.traderId()).isEqualTo(trader);
            assertThat(allocation.isActive()).isTrue();
            assertThat(allocation.validFrom()).isEqualTo(clock.instant());
            assertThat(stalls.find(council.id(), stall.id()).orElseThrow().status())
                    .isEqualTo(StallStatus.ALLOCATED);
        }

        @Test
        @DisplayName("allocating again to the same trader changes nothing (safe to repeat)")
        void allocateTwiceSameTrader() {
            Stall stall = importOne("B", "114");
            UUID trader = traders.add(council.id());
            StallAllocation first = allocate.execute(council.id(), stall.id(), trader);

            StallAllocation second = allocate.execute(council.id(), stall.id(), trader);

            assertThat(second).isEqualTo(first);
            assertThat(allocations.all).hasSize(1);
        }

        @Test
        @DisplayName("a stall held by one trader cannot be allocated to another")
        void allocateToSecondTrader() {
            Stall stall = importOne("B", "114");
            allocate.execute(council.id(), stall.id(), traders.add(council.id()));

            assertThatThrownBy(() -> allocate.execute(council.id(), stall.id(), traders.add(council.id())))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already allocated");
        }

        @Test
        @DisplayName("a trader from another council is not found")
        void traderOfAnotherCouncil() {
            Stall stall = importOne("B", "114");
            Council other = createCouncil.execute("Kitwe City Council", null);
            UUID foreignTrader = traders.add(other.id());

            assertThatThrownBy(() -> allocate.execute(council.id(), stall.id(), foreignTrader))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a stall that does not exist is not found")
        void unknownStall() {
            assertThatThrownBy(() -> allocate.execute(council.id(), UUID.randomUUID(), traders.add(council.id())))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a suspended stall cannot be allocated")
        void suspendedStall() {
            Stall stall = importOne("B", "114");
            stalls.setStatus(council.id(), stall.id(), StallStatus.SUSPENDED);

            assertThatThrownBy(() -> allocate.execute(council.id(), stall.id(), traders.add(council.id())))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("suspended");
        }

        @Test
        @DisplayName("freeing ends the allocation, keeps it as history, and makes the stall vacant")
        void freeStall() {
            Stall stall = importOne("B", "114");
            allocate.execute(council.id(), stall.id(), traders.add(council.id()));

            boolean freed = free.execute(council.id(), stall.id());

            assertThat(freed).isTrue();
            assertThat(stalls.find(council.id(), stall.id()).orElseThrow().status()).isEqualTo(StallStatus.VACANT);
            assertThat(allocations.all).hasSize(1);
            assertThat(allocations.all.get(0).isActive()).isFalse();
            assertThat(allocations.all.get(0).validTo()).isEqualTo(clock.instant());
        }

        @Test
        @DisplayName("freeing a stall that is not allocated does nothing")
        void freeTwice() {
            Stall stall = importOne("B", "114");
            allocate.execute(council.id(), stall.id(), traders.add(council.id()));
            free.execute(council.id(), stall.id());

            assertThat(free.execute(council.id(), stall.id())).isFalse();
            assertThat(allocations.all).hasSize(1);
        }

        @Test
        @DisplayName("a freed stall can be allocated again, and the history keeps both")
        void reallocate() {
            Stall stall = importOne("B", "114");
            allocate.execute(council.id(), stall.id(), traders.add(council.id()));
            free.execute(council.id(), stall.id());

            allocate.execute(council.id(), stall.id(), traders.add(council.id()));

            assertThat(allocations.all).hasSize(2);
            assertThat(allocations.all.stream().filter(StallAllocation::isActive)).hasSize(1);
        }
    }
}
