package zm.msika.core.trader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.agent.Agent;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.agent.RegisterAgent;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CreateCouncil;
import zm.msika.core.market.CreateMarket;
import zm.msika.core.market.Market;
import zm.msika.core.phone.Language;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.plate.GenerateMarketPlates;
import zm.msika.core.plate.PlateRenderer;
import zm.msika.core.plate.StallPlate;
import zm.msika.core.stall.ImportStalls;

/** Specification for registering traders and agents, and for building the plates of a market. */
class PeopleAndPlatesTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);
    private final Fakes.Councils councils = new Fakes.Councils();
    private final Fakes.Markets markets = new Fakes.Markets();
    private final Fakes.Stalls stalls = new Fakes.Stalls();

    private final Map<String, Trader> traderStore = new HashMap<>();
    private final TraderRepository traders = new TraderRepository() {
        @Override
        public void add(Trader trader) {
            traderStore.put(trader.councilId() + "/" + trader.phone().e164(), trader);
        }

        @Override
        public Optional<Trader> find(UUID councilId, UUID traderId) {
            return traderStore.values().stream()
                    .filter(t -> t.councilId().equals(councilId) && t.id().equals(traderId)).findFirst();
        }

        @Override
        public List<Trader> list(UUID councilId) {
            return traderStore.values().stream().filter(t -> t.councilId().equals(councilId)).toList();
        }

        @Override
        public boolean existsByPhone(UUID councilId, PhoneNumber phone) {
            return traderStore.containsKey(councilId + "/" + phone.e164());
        }
    };

    private final List<Agent> agentStore = new ArrayList<>();
    private final AgentRepository agents = new AgentRepository() {
        @Override
        public void add(Agent agent) {
            agentStore.add(agent);
        }

        @Override
        public List<Agent> list(UUID councilId) {
            return agentStore.stream().filter(a -> a.councilId().equals(councilId)).toList();
        }

        @Override
        public java.util.Optional<Agent> find(UUID councilId, UUID agentId) {
            return agentStore.stream().filter(a -> a.councilId().equals(councilId) && a.id().equals(agentId)).findFirst();
        }

        @Override
        public boolean existsByPhone(UUID councilId, PhoneNumber phone) {
            return agentStore.stream().anyMatch(a -> a.councilId().equals(councilId) && a.phone().equals(phone));
        }
    };

    private final RegisterTrader registerTrader = new RegisterTrader(councils, traders, clock);
    private final RegisterAgent registerAgent = new RegisterAgent(councils, agents, clock);

    private Council council;

    @BeforeEach
    void setUp() {
        council = new CreateCouncil(councils, clock).execute("Lusaka City Council", null);
    }

    @Nested
    @DisplayName("registering traders")
    class Traders {

        @Test
        @DisplayName("a trader is stored with a normalised number, no consent yet, and English by default")
        void registers() {
            Trader trader = registerTrader.execute(council.id(), "0977 000 739", "  Mary Banda ", null);

            assertThat(trader.phone().e164()).isEqualTo("+260977000739");
            assertThat(trader.displayName()).isEqualTo("Mary Banda");
            assertThat(trader.language()).isEqualTo(Language.ENGLISH);
            assertThat(trader.consentLevySweepAt()).isNull();
            assertThat(trader.consentDataAt()).isNull();
        }

        @Test
        @DisplayName("the same number written three ways is the same trader, so the second is a conflict")
        void sameNumberDifferentSpelling() {
            registerTrader.execute(council.id(), "0977000739", "Mary Banda", "en");

            for (String again : new String[] {"+260977000739", "260977000739", "0977 000 739"}) {
                assertThatThrownBy(() -> registerTrader.execute(council.id(), again, "Someone Else", "en"))
                        .as(again).isInstanceOf(ConflictException.class);
            }
            assertThat(traders.list(council.id())).hasSize(1);
        }

        @Test
        @DisplayName("two councils may each have a trader with the same number")
        void sameNumberOtherCouncil() {
            Council other = new CreateCouncil(councils, clock).execute("Kitwe City Council", null);
            registerTrader.execute(council.id(), "0977000739", "Mary Banda", "en");

            assertThat(registerTrader.execute(other.id(), "0977000739", "Mary Banda", "en")).isNotNull();
        }

        @Test
        @DisplayName("a bad number, name or language is refused, and the council must exist")
        void refusals() {
            assertThatThrownBy(() -> registerTrader.execute(council.id(), "12345", "Mary Banda", "en"))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> registerTrader.execute(council.id(), "0977000739", " ", "en"))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> registerTrader.execute(council.id(), "0977000739", "Mary Banda", "fr"))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> registerTrader.execute(UUID.randomUUID(), "0977000739", "Mary Banda", "en"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("printing a trader never shows the full phone number")
        void toStringIsMasked() {
            Trader trader = registerTrader.execute(council.id(), "0977000739", "Mary Banda", "en");

            assertThat(trader.toString()).contains("0977***739").doesNotContain("000739");
        }

        @Test
        @DisplayName("Nyanja and Bemba are accepted")
        void languages() {
            assertThat(registerTrader.execute(council.id(), "0977000001", "Chola Mwila", "bem").language())
                    .isEqualTo(Language.BEMBA);
            assertThat(registerTrader.execute(council.id(), "0977000002", "Banda Phiri", "NY").language())
                    .isEqualTo(Language.NYANJA);
        }
    }

    @Nested
    @DisplayName("registering agents")
    class Agents {

        @Test
        @DisplayName("an agent is stored active, with a normalised number, and printing never shows it in full")
        void registers() {
            Agent agent = registerAgent.execute(council.id(), "260966000001", "Mwila Chanda");

            assertThat(agent.phone().e164()).isEqualTo("+260966000001");
            assertThat(agent.active()).isTrue();
            assertThat(agent.toString()).doesNotContain("000001").contains("0966***001");
        }

        @Test
        @DisplayName("registering the same agent twice, in any spelling, is a conflict")
        void duplicate() {
            registerAgent.execute(council.id(), "0966000001", "Mwila Chanda");

            assertThatThrownBy(() -> registerAgent.execute(council.id(), "+260966000001", "Mwila Chanda"))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("plates")
    class Plates {

        private PlateRenderer capturing(List<List<StallPlate>> captured) {
            return plates -> {
                captured.add(plates);
                return new byte[] {1, 2, 3};
            };
        }

        @Test
        @DisplayName("the QR text holds the stall code and the token, and reads back")
        void qrRoundTrip() {
            StallPlate plate = new StallPlate("SOW-B-114", "Soweto Market", "tok_ABC-123_xyz");

            assertThat(plate.qrText()).isEqualTo("msika://stall/SOW-B-114?t=tok_ABC-123_xyz");
            assertThat(StallPlate.parseQr(plate.qrText()))
                    .isEqualTo(new StallPlate.Scanned("SOW-B-114", "tok_ABC-123_xyz"));
        }

        @Test
        @DisplayName("text that is not one of our QR codes reads as nothing")
        void foreignQr() {
            for (String other : new String[] {null, "", "https://example.test", "msika://stall/", "msika://stall/SOW-B-1",
                "msika://stall/SOW-B-1?t=", "msika://stall/?t=abc"}) {
                assertThat(StallPlate.parseQr(other)).as(String.valueOf(other)).isNull();
            }
        }

        @Test
        @DisplayName("printing a plate never shows the secret token")
        void tokenNotInToString() {
            assertThat(new StallPlate("SOW-B-114", "Soweto Market", "secret-token-value-1").toString())
                    .doesNotContain("secret-token-value-1");
        }

        @Test
        @DisplayName("a market's plates are in stall-code order, one per stall, with the market's name")
        void oneToOneInOrder() {
            Market market = new CreateMarket(councils, markets, clock).execute(council.id(), "SOW", "Soweto Market", "");
            new ImportStalls(markets, stalls).execute(council.id(), market.id(),
                    "section,number,class\nC,7,STANDARD\nB,114,STANDARD\nB,115,STANDARD\n");
            List<List<StallPlate>> captured = new ArrayList<>();

            byte[] pdf = new GenerateMarketPlates(markets, stalls, capturing(captured)).execute(council.id(), market.id());

            assertThat(pdf).isEqualTo(new byte[] {1, 2, 3});
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0)).extracting(StallPlate::marketName).containsOnly("Soweto Market");
            assertThat(captured.get(0)).hasSize(3);
            assertThat(captured.get(0).stream().map(StallPlate::stallCode).toList())
                    .containsExactlyInAnyOrder("SOW-B-114", "SOW-B-115", "SOW-C-7");
            assertThat(captured.get(0).stream().map(StallPlate::qrToken).distinct()).hasSize(3);
        }

        @Test
        @DisplayName("a market with no stalls has nothing to print, and an unknown market is not found")
        void emptyAndUnknown() {
            Market market = new CreateMarket(councils, markets, clock).execute(council.id(), "SOW", "Soweto Market", "");
            GenerateMarketPlates generate = new GenerateMarketPlates(markets, stalls, capturing(new ArrayList<>()));

            assertThatThrownBy(() -> generate.execute(council.id(), market.id())).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> generate.execute(council.id(), UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
