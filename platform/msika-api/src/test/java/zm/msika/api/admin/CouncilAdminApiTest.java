package zm.msika.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import zm.msika.api.PostgresIntegrationTest;
import zm.msika.api.tenancy.TenantContext;

/**
 * Councils, markets, stalls and allocations through the real HTTP API and a real Postgres.
 *
 * <p>Each test makes its own council, so tests do not depend on each other or on an empty database.
 */
class CouncilAdminApiTest extends PostgresIntegrationTest {

    record CouncilBody(UUID id, String name, String timezone) {}

    record MarketBody(UUID id, UUID councilId, String code, String name, String location, String dayCloseTime) {}

    record ImportBody(int created) {}

    record StallBody(UUID id, UUID marketId, String code, String stallClass, String status, String qrToken) {}

    record AllocationBody(UUID id, UUID stallId, UUID traderId, String validFrom) {}

    record RowBody(int row, String message) {}

    record ErrorBody(String error, String message, List<RowBody> rows) {}

    record Result<T>(int status, T body) {}

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private <T> Result<T> call(HttpMethod method, String path, Object body, MediaType type, Class<T> responseType) {
        RestClient.RequestBodySpec spec = client().method(method).uri(path);
        if (body != null) {
            spec = spec.contentType(type).body(body);
        }
        return spec.exchange((request, response) -> new Result<>(
                response.getStatusCode().value(),
                responseType == Void.class ? null : response.bodyTo(responseType)));
    }

    private <T> Result<T> post(String path, Object body, Class<T> responseType) {
        return call(HttpMethod.POST, path, body, MediaType.APPLICATION_JSON, responseType);
    }

    private <T> Result<T> get(String path, Class<T> responseType) {
        return call(HttpMethod.GET, path, null, null, responseType);
    }

    private Result<ImportBody> importCsv(UUID council, UUID market, String csv) {
        return call(HttpMethod.POST, "/api/v1/councils/" + council + "/markets/" + market + "/stalls/import",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.parseMediaType("text/csv"),
                ImportBody.class);
    }

    private Result<ErrorBody> importCsvExpectingError(UUID council, UUID market, String csv) {
        return call(HttpMethod.POST, "/api/v1/councils/" + council + "/markets/" + market + "/stalls/import",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.parseMediaType("text/csv"),
                ErrorBody.class);
    }

    private CouncilBody newCouncil() {
        Result<CouncilBody> r = post("/api/v1/councils",
                new CouncilAdminController.CreateCouncilRequest("Test Council " + UUID.randomUUID(), null),
                CouncilBody.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private MarketBody newMarket(UUID council, String code) {
        Result<MarketBody> r = post("/api/v1/councils/" + council + "/markets",
                new CouncilAdminController.CreateMarketRequest(code, "Market " + code, "Somewhere"),
                MarketBody.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private List<StallBody> stallsOf(UUID council, UUID market) {
        Result<StallBody[]> r = get("/api/v1/councils/" + council + "/markets/" + market + "/stalls",
                StallBody[].class);
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

    private static final String THREE_STALLS = "section,number,class\nB,114,STANDARD\nB,115,STANDARD\nC,7,LARGE\n";

    @Test
    @DisplayName("create a council and a market, and list the markets")
    void createCouncilAndMarket() {
        CouncilBody council = newCouncil();
        assertThat(council.timezone()).isEqualTo("Africa/Lusaka");

        MarketBody market = newMarket(council.id(), "sow");

        assertThat(market.code()).isEqualTo("SOW");
        assertThat(market.dayCloseTime()).isEqualTo("20:00");
        Result<MarketBody[]> list = get("/api/v1/councils/" + council.id() + "/markets", MarketBody[].class);
        assertThat(list.body()).extracting(MarketBody::code).containsExactly("SOW");
    }

    @Test
    @DisplayName("the same market code twice in one council is refused with 409")
    void duplicateMarketCode() {
        CouncilBody council = newCouncil();
        newMarket(council.id(), "SOW");

        Result<ErrorBody> second = post("/api/v1/councils/" + council.id() + "/markets",
                new CouncilAdminController.CreateMarketRequest("sow", "Again", ""), ErrorBody.class);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().error()).isEqualTo("conflict");
    }

    @Test
    @DisplayName("a market in a council that does not exist is 404; a bad market code is 400")
    void councilNotFoundAndBadInput() {
        assertThat(post("/api/v1/councils/" + UUID.randomUUID() + "/markets",
                new CouncilAdminController.CreateMarketRequest("ABC", "Nowhere", ""), ErrorBody.class).status())
                .isEqualTo(404);

        CouncilBody council = newCouncil();
        Result<ErrorBody> bad = post("/api/v1/councils/" + council.id() + "/markets",
                new CouncilAdminController.CreateMarketRequest("S", "Soweto", ""), ErrorBody.class);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().message()).contains("market code");
    }

    @Test
    @DisplayName("bulk import creates vacant stalls with codes like SOW-B-114 and their own QR tokens")
    void bulkImport() {
        CouncilBody council = newCouncil();
        MarketBody market = newMarket(council.id(), "SOW");

        Result<ImportBody> result = importCsv(council.id(), market.id(), THREE_STALLS);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().created()).isEqualTo(3);
        List<StallBody> stalls = stallsOf(council.id(), market.id());
        assertThat(stalls).extracting(StallBody::code).containsExactly("SOW-B-114", "SOW-B-115", "SOW-C-7");
        assertThat(stalls).extracting(StallBody::status).containsOnly("VACANT");
        assertThat(stalls).extracting(StallBody::stallClass).containsExactly("STANDARD", "STANDARD", "LARGE");
        assertThat(new HashSet<>(stalls.stream().map(StallBody::qrToken).toList())).hasSize(3);
        assertThat(stalls).allSatisfy(s -> assertThat(s.qrToken()).hasSize(22));
    }

    @Test
    @DisplayName("a CSV with a bad row is rejected with the row number, and nothing is created")
    void bulkImportWithBadRow() {
        CouncilBody council = newCouncil();
        MarketBody market = newMarket(council.id(), "SOW");

        Result<ErrorBody> result = importCsvExpectingError(council.id(), market.id(),
                "section,number,class\nB,114,STANDARD\nB,oops,STANDARD\nB,116,STANDARD\n");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().error()).isEqualTo("invalid_input");
        assertThat(result.body().rows()).hasSize(1);
        assertThat(result.body().rows().get(0).row()).isEqualTo(3);
        assertThat(result.body().rows().get(0).message()).contains("number");
        assertThat(stallsOf(council.id(), market.id())).isEmpty();
    }

    @Test
    @DisplayName("uploading the same file twice is refused with 409 and creates nothing more")
    void bulkImportTwice() {
        CouncilBody council = newCouncil();
        MarketBody market = newMarket(council.id(), "SOW");
        importCsv(council.id(), market.id(), THREE_STALLS);

        Result<ErrorBody> second = importCsvExpectingError(council.id(), market.id(), THREE_STALLS);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().message()).contains("SOW-B-114");
        assertThat(stallsOf(council.id(), market.id())).hasSize(3);
    }

    @Test
    @DisplayName("allocate a stall to a trader, repeat it safely, refuse a second trader, then free it")
    void allocateAndFree() {
        CouncilBody council = newCouncil();
        MarketBody market = newMarket(council.id(), "SOW");
        importCsv(council.id(), market.id(), THREE_STALLS);
        UUID stall = stallsOf(council.id(), market.id()).get(0).id();
        UUID trader = newTrader(council.id());
        UUID otherTrader = newTrader(council.id());
        String path = "/api/v1/councils/" + council.id() + "/stalls/" + stall + "/allocation";

        Result<AllocationBody> first = call(HttpMethod.PUT, path,
                new CouncilAdminController.AllocateRequest(trader), MediaType.APPLICATION_JSON, AllocationBody.class);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().traderId()).isEqualTo(trader);
        assertThat(stallsOf(council.id(), market.id()).get(0).status()).isEqualTo("ALLOCATED");

        Result<AllocationBody> repeated = call(HttpMethod.PUT, path,
                new CouncilAdminController.AllocateRequest(trader), MediaType.APPLICATION_JSON, AllocationBody.class);
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(repeated.body().id()).as("the same allocation, not a new one").isEqualTo(first.body().id());

        Result<ErrorBody> clash = call(HttpMethod.PUT, path,
                new CouncilAdminController.AllocateRequest(otherTrader), MediaType.APPLICATION_JSON, ErrorBody.class);
        assertThat(clash.status()).isEqualTo(409);

        assertThat(call(HttpMethod.DELETE, path, null, null, Void.class).status()).isEqualTo(204);
        assertThat(stallsOf(council.id(), market.id()).get(0).status()).isEqualTo("VACANT");
        assertThat(call(HttpMethod.DELETE, path, null, null, Void.class).status())
                .as("freeing a free stall is safe").isEqualTo(204);

        Integer history = tenant.inCouncil(council.id(), () -> jdbc
                .sql("select count(*) from stall_allocation where stall_id = :id and valid_to is not null")
                .param("id", stall).query(Integer.class).single());
        assertThat(history).as("the ended allocation is kept as history").isEqualTo(1);

        Result<AllocationBody> again = call(HttpMethod.PUT, path,
                new CouncilAdminController.AllocateRequest(otherTrader), MediaType.APPLICATION_JSON, AllocationBody.class);
        assertThat(again.status()).as("a freed stall can be allocated to someone else").isEqualTo(200);
    }

    @Test
    @DisplayName("a trader or stall of another council is not found")
    void councilsAreSeparate() {
        CouncilBody a = newCouncil();
        CouncilBody b = newCouncil();
        MarketBody marketA = newMarket(a.id(), "SOW");
        newMarket(b.id(), "SOW");
        importCsv(a.id(), marketA.id(), THREE_STALLS);
        UUID stallOfA = stallsOf(a.id(), marketA.id()).get(0).id();
        UUID traderOfB = newTrader(b.id());

        Result<ErrorBody> foreignTrader = call(HttpMethod.PUT,
                "/api/v1/councils/" + a.id() + "/stalls/" + stallOfA + "/allocation",
                new CouncilAdminController.AllocateRequest(traderOfB), MediaType.APPLICATION_JSON, ErrorBody.class);
        assertThat(foreignTrader.status()).isEqualTo(404);

        Result<ErrorBody> foreignStall = call(HttpMethod.PUT,
                "/api/v1/councils/" + b.id() + "/stalls/" + stallOfA + "/allocation",
                new CouncilAdminController.AllocateRequest(traderOfB), MediaType.APPLICATION_JSON, ErrorBody.class);
        assertThat(foreignStall.status()).isEqualTo(404);

        assertThat(get("/api/v1/councils/" + b.id() + "/markets/" + marketA.id() + "/stalls", ErrorBody.class).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the database itself refuses a stall in a market of another council")
    void databaseRefusesCrossCouncilStall() {
        CouncilBody a = newCouncil();
        CouncilBody b = newCouncil();
        MarketBody marketOfB = newMarket(b.id(), "SOW");

        assertThatThrownBy(() -> tenant.inCouncil(a.id(), () -> jdbc
                .sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                        + "values (:id, :council, :market, 'SOW-X-1', 'STANDARD', 'VACANT', :token)")
                .param("id", UUID.randomUUID())
                .param("council", a.id())
                .param("market", marketOfB.id())
                .param("token", "t".repeat(22))
                .update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the database itself refuses a second active allocation for a stall")
    void databaseRefusesTwoActiveAllocations() {
        CouncilBody council = newCouncil();
        MarketBody market = newMarket(council.id(), "SOW");
        importCsv(council.id(), market.id(), THREE_STALLS);
        UUID stall = stallsOf(council.id(), market.id()).get(0).id();
        String insert = "insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) "
                + "values (:id, :council, :stall, :trader, now())";
        UUID firstTrader = newTrader(council.id());
        UUID secondTrader = newTrader(council.id());
        tenant.inCouncil(council.id(), () -> jdbc.sql(insert).param("id", UUID.randomUUID())
                .param("council", council.id()).param("stall", stall).param("trader", firstTrader).update());

        assertThatThrownBy(() -> tenant.inCouncil(council.id(), () -> jdbc.sql(insert)
                .param("id", UUID.randomUUID()).param("council", council.id())
                .param("stall", stall).param("trader", secondTrader).update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
