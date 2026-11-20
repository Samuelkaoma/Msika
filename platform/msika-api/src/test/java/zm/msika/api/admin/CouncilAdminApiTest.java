package zm.msika.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.api.tenancy.TenantContext;

/**
 * Councils, markets, stalls and allocations through the real HTTP API and a real Postgres, signed in
 * as a council admin. Each test makes its own council, so tests do not depend on each other.
 */
class CouncilAdminApiTest extends ApiTest {

    record MarketBody(UUID id, UUID councilId, String code, String name, String location, String dayCloseTime) {}

    record ImportBody(int created) {}

    record StallBody(UUID id, UUID marketId, String code, String stallClass, String status, String qrToken) {}

    record AllocationBody(UUID id, UUID stallId, UUID traderId, String validFrom) {}

    record RowBody(int row, String message) {}

    record ErrorBody(String error, String message, List<RowBody> rows) {}

    record MarketRequest(String code, String name, String location) {}

    record AllocateRequest(UUID traderId) {}

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    private static final String THREE_STALLS = "section,number,class\nB,114,STANDARD\nB,115,STANDARD\nC,7,LARGE\n";

    private static String councilPath(UUID council) {
        return "/api/v1/councils/" + council;
    }

    private MarketBody newMarket(Fixture council, String code) {
        Result<MarketBody> r = api().post(council.adminToken(), councilPath(council.councilId()) + "/markets",
                new MarketRequest(code, "Market " + code, "Somewhere"), MarketBody.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private Result<ImportBody> importCsv(Fixture council, UUID market, String csv) {
        return api().postCsv(council.adminToken(),
                councilPath(council.councilId()) + "/markets/" + market + "/stalls/import", csv, ImportBody.class);
    }

    private Result<ErrorBody> importCsvExpectingError(Fixture council, UUID market, String csv) {
        return api().postCsv(council.adminToken(),
                councilPath(council.councilId()) + "/markets/" + market + "/stalls/import", csv, ErrorBody.class);
    }

    private List<StallBody> stallsOf(Fixture council, UUID market) {
        Result<StallBody[]> r = api().get(council.adminToken(),
                councilPath(council.councilId()) + "/markets/" + market + "/stalls", StallBody[].class);
        assertThat(r.status()).isEqualTo(200);
        return List.of(r.body());
    }

    private UUID newTrader(UUID council) {
        UUID id = UUID.randomUUID();
        // Row-level security: the insert must say which council it is for, like the real app does.
        tenant.inCouncil(council, () -> jdbc
                .sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :council, :msisdn, 'Test Trader')")
                .param("id", id)
                .param("council", council)
                .param("msisdn", "+26097700" + (1000 + Math.abs(id.hashCode() % 9000)))
                .update());
        return id;
    }

    @Test
    @DisplayName("create a council and a market, and list the markets")
    void createCouncilAndMarket() {
        Fixture council = newCouncil();

        MarketBody market = newMarket(council, "sow");

        assertThat(market.code()).isEqualTo("SOW");
        assertThat(market.dayCloseTime()).isEqualTo("20:00");
        Result<MarketBody[]> list = api().get(council.adminToken(), councilPath(council.councilId()) + "/markets",
                MarketBody[].class);
        assertThat(list.body()).extracting(MarketBody::code).containsExactly("SOW");
    }

    @Test
    @DisplayName("the same market code twice in one council is refused with 409")
    void duplicateMarketCode() {
        Fixture council = newCouncil();
        newMarket(council, "SOW");

        Result<ErrorBody> second = api().post(council.adminToken(), councilPath(council.councilId()) + "/markets",
                new MarketRequest("sow", "Again", ""), ErrorBody.class);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().error()).isEqualTo("conflict");
    }

    @Test
    @DisplayName("a market in a council that does not exist is 404; a bad market code is 400")
    void councilNotFoundAndBadInput() {
        assertThat(api().post(operatorToken(), councilPath(UUID.randomUUID()) + "/markets",
                new MarketRequest("ABC", "Nowhere", ""), ErrorBody.class).status()).isEqualTo(404);

        Fixture council = newCouncil();
        Result<ErrorBody> bad = api().post(council.adminToken(), councilPath(council.councilId()) + "/markets",
                new MarketRequest("S", "Soweto", ""), ErrorBody.class);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().message()).contains("market code");
    }

    @Test
    @DisplayName("bulk import creates vacant stalls with codes like SOW-B-114 and their own QR tokens")
    void bulkImport() {
        Fixture council = newCouncil();
        MarketBody market = newMarket(council, "SOW");

        Result<ImportBody> result = importCsv(council, market.id(), THREE_STALLS);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().created()).isEqualTo(3);
        List<StallBody> stalls = stallsOf(council, market.id());
        assertThat(stalls).extracting(StallBody::code).containsExactly("SOW-B-114", "SOW-B-115", "SOW-C-7");
        assertThat(stalls).extracting(StallBody::status).containsOnly("VACANT");
        assertThat(stalls).extracting(StallBody::stallClass).containsExactly("STANDARD", "STANDARD", "LARGE");
        assertThat(new HashSet<>(stalls.stream().map(StallBody::qrToken).toList())).hasSize(3);
        assertThat(stalls).allSatisfy(s -> assertThat(s.qrToken()).hasSize(22));
    }

    @Test
    @DisplayName("a CSV with a bad row is rejected with the row number, and nothing is created")
    void bulkImportWithBadRow() {
        Fixture council = newCouncil();
        MarketBody market = newMarket(council, "SOW");

        Result<ErrorBody> result = importCsvExpectingError(council, market.id(),
                "section,number,class\nB,114,STANDARD\nB,oops,STANDARD\nB,116,STANDARD\n");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().error()).isEqualTo("invalid_input");
        assertThat(result.body().rows()).hasSize(1);
        assertThat(result.body().rows().get(0).row()).isEqualTo(3);
        assertThat(result.body().rows().get(0).message()).contains("number");
        assertThat(stallsOf(council, market.id())).isEmpty();
    }

    @Test
    @DisplayName("uploading the same file twice is refused with 409 and creates nothing more")
    void bulkImportTwice() {
        Fixture council = newCouncil();
        MarketBody market = newMarket(council, "SOW");
        importCsv(council, market.id(), THREE_STALLS);

        Result<ErrorBody> second = importCsvExpectingError(council, market.id(), THREE_STALLS);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().message()).contains("SOW-B-114");
        assertThat(stallsOf(council, market.id())).hasSize(3);
    }

    @Test
    @DisplayName("allocate a stall to a trader, repeat it safely, refuse a second trader, then free it")
    void allocateAndFree() {
        Fixture council = newCouncil();
        MarketBody market = newMarket(council, "SOW");
        importCsv(council, market.id(), THREE_STALLS);
        UUID stall = stallsOf(council, market.id()).get(0).id();
        UUID trader = newTrader(council.councilId());
        UUID otherTrader = newTrader(council.councilId());
        String path = councilPath(council.councilId()) + "/stalls/" + stall + "/allocation";
        String token = council.adminToken();

        Result<AllocationBody> first = api().put(token, path, new AllocateRequest(trader), AllocationBody.class);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().traderId()).isEqualTo(trader);
        assertThat(stallsOf(council, market.id()).get(0).status()).isEqualTo("ALLOCATED");

        Result<AllocationBody> repeated = api().put(token, path, new AllocateRequest(trader), AllocationBody.class);
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(repeated.body().id()).as("the same allocation, not a new one").isEqualTo(first.body().id());

        Result<ErrorBody> clash = api().put(token, path, new AllocateRequest(otherTrader), ErrorBody.class);
        assertThat(clash.status()).isEqualTo(409);

        assertThat(api().delete(token, path, Void.class).status()).isEqualTo(204);
        assertThat(stallsOf(council, market.id()).get(0).status()).isEqualTo("VACANT");
        assertThat(api().delete(token, path, Void.class).status()).as("freeing a free stall is safe").isEqualTo(204);

        Integer history = tenant.inCouncil(council.councilId(), () -> jdbc
                .sql("select count(*) from stall_allocation where stall_id = :id and valid_to is not null")
                .param("id", stall).query(Integer.class).single());
        assertThat(history).as("the ended allocation is kept as history").isEqualTo(1);

        Result<AllocationBody> again = api().put(token, path, new AllocateRequest(otherTrader), AllocationBody.class);
        assertThat(again.status()).as("a freed stall can be allocated to someone else").isEqualTo(200);
    }

    @Test
    @DisplayName("a trader or stall of another council is not found")
    void councilsAreSeparate() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();
        MarketBody marketA = newMarket(a, "SOW");
        newMarket(b, "SOW");
        importCsv(a, marketA.id(), THREE_STALLS);
        UUID stallOfA = stallsOf(a, marketA.id()).get(0).id();
        UUID traderOfB = newTrader(b.councilId());
        // The operator may act in any council, which lets us ask each council about the other's things.
        String operator = operatorToken();

        Result<ErrorBody> foreignTrader = api().put(operator,
                councilPath(a.councilId()) + "/stalls/" + stallOfA + "/allocation",
                new AllocateRequest(traderOfB), ErrorBody.class);
        assertThat(foreignTrader.status()).isEqualTo(404);

        Result<ErrorBody> foreignStall = api().put(operator,
                councilPath(b.councilId()) + "/stalls/" + stallOfA + "/allocation",
                new AllocateRequest(traderOfB), ErrorBody.class);
        assertThat(foreignStall.status()).isEqualTo(404);

        assertThat(api().get(operator, councilPath(b.councilId()) + "/markets/" + marketA.id() + "/stalls",
                ErrorBody.class).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the database itself refuses a stall in a market of another council")
    void databaseRefusesCrossCouncilStall() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();
        MarketBody marketOfB = newMarket(b, "SOW");

        assertThatThrownBy(() -> tenant.inCouncil(a.councilId(), () -> jdbc
                .sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                        + "values (:id, :council, :market, 'SOW-X-1', 'STANDARD', 'VACANT', :token)")
                .param("id", UUID.randomUUID())
                .param("council", a.councilId())
                .param("market", marketOfB.id())
                .param("token", "t".repeat(22))
                .update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the database itself refuses a second active allocation for a stall")
    void databaseRefusesTwoActiveAllocations() {
        Fixture council = newCouncil();
        MarketBody market = newMarket(council, "SOW");
        importCsv(council, market.id(), THREE_STALLS);
        UUID stall = stallsOf(council, market.id()).get(0).id();
        String insert = "insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) "
                + "values (:id, :council, :stall, :trader, now())";
        UUID firstTrader = newTrader(council.councilId());
        UUID secondTrader = newTrader(council.councilId());
        tenant.inCouncil(council.councilId(), () -> jdbc.sql(insert).param("id", UUID.randomUUID())
                .param("council", council.councilId()).param("stall", stall).param("trader", firstTrader).update());

        assertThatThrownBy(() -> tenant.inCouncil(council.councilId(), () -> jdbc.sql(insert)
                .param("id", UUID.randomUUID()).param("council", council.councilId())
                .param("stall", stall).param("trader", secondTrader).update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
