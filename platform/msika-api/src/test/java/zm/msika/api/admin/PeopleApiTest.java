package zm.msika.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.api.plate.QrTestSupport;
import zm.msika.api.tenancy.TenantContext;

/** Traders, agents and printable stall plates through the real HTTP API and a real Postgres. */
class PeopleApiTest extends ApiTest {

    record TraderBody(UUID id, UUID councilId, String phone, String displayName, String language,
            String consentLevySweepAt, String consentDataAt) {}

    record AgentBody(UUID id, UUID councilId, String phone, String displayName, boolean active) {}

    record ErrorBody(String error, String message) {}

    record TraderRequest(String phone, String displayName, String language) {}

    record AgentRequest(String phone, String displayName) {}

    record MarketRequest(String code, String name, String location) {}

    record MarketBody(UUID id, String code) {}

    record StallBody(UUID id, String code, String qrToken) {}

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    private static String base(Fixture council) {
        return "/api/v1/councils/" + council.councilId();
    }

    private MarketBody marketWithStalls(Fixture council, String code, String name, String csv) {
        MarketBody market = api().post(council.adminToken(), base(council) + "/markets",
                new MarketRequest(code, name, ""), MarketBody.class).body();
        if (csv != null) {
            assertThat(api().postCsv(council.adminToken(), base(council) + "/markets/" + market.id() + "/stalls/import",
                    csv, Void.class).status()).isEqualTo(201);
        }
        return market;
    }

    @Test
    @DisplayName("0977..., 260977... and +260977... are the same trader: the first registers, the rest are 409")
    void phoneNormalisationThroughTheApi() {
        Fixture council = newCouncil();

        Result<TraderBody> first = api().post(council.adminToken(), base(council) + "/traders",
                new TraderRequest("0977 000 739", "Mary Banda", null), TraderBody.class);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().phone()).isEqualTo("+260977000739");
        assertThat(first.body().language()).isEqualTo("en");
        assertThat(first.body().consentLevySweepAt()).isNull();

        for (String again : new String[] {"+260977000739", "260977000739", "0977000739", "00260977000739"}) {
            assertThat(api().post(council.adminToken(), base(council) + "/traders",
                    new TraderRequest(again, "Mary Again", "en"), ErrorBody.class).status())
                    .as(again).isEqualTo(409);
        }

        Result<TraderBody[]> list = api().get(council.adminToken(), base(council) + "/traders", TraderBody[].class);
        assertThat(list.body()).hasSize(1);
    }

    @Test
    @DisplayName("a bad phone number, name or language is a 400")
    void badInput() {
        Fixture council = newCouncil();
        String token = council.adminToken();

        assertThat(api().post(token, base(council) + "/traders", new TraderRequest("12345", "Mary Banda", "en"),
                ErrorBody.class).body().message()).contains("phone number");
        assertThat(api().post(token, base(council) + "/traders", new TraderRequest("+27821234567", "Mary Banda", "en"),
                Void.class).status()).as("a South African number").isEqualTo(400);
        assertThat(api().post(token, base(council) + "/traders", new TraderRequest("0977000739", " ", "en"),
                Void.class).status()).isEqualTo(400);
        assertThat(api().post(token, base(council) + "/traders", new TraderRequest("0977000739", "Mary Banda", "fr"),
                Void.class).status()).isEqualTo(400);
        assertThat(api().post(token, base(council) + "/traders", new TraderRequest(null, "Mary Banda", "en"),
                Void.class).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("two councils may each register the same number; each sees only its own traders")
    void councilsAreSeparate() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();

        assertThat(api().post(a.adminToken(), base(a) + "/traders", new TraderRequest("0977000739", "Mary A", "en"),
                Void.class).status()).isEqualTo(201);
        assertThat(api().post(b.adminToken(), base(b) + "/traders", new TraderRequest("0977000739", "Mary B", "en"),
                Void.class).status()).isEqualTo(201);

        assertThat(api().get(a.adminToken(), base(a) + "/traders", TraderBody[].class).body())
                .extracting(TraderBody::displayName).containsExactly("Mary A");
        assertThat(api().get(b.adminToken(), base(b) + "/traders", TraderBody[].class).body())
                .extracting(TraderBody::displayName).containsExactly("Mary B");
    }

    @Test
    @DisplayName("agents are registered and listed the same way")
    void agents() {
        Fixture council = newCouncil();

        Result<AgentBody> created = api().post(council.adminToken(), base(council) + "/agents",
                new AgentRequest("0966 000 001", "Mwila Chanda"), AgentBody.class);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().phone()).isEqualTo("+260966000001");
        assertThat(created.body().active()).isTrue();
        assertThat(api().post(council.adminToken(), base(council) + "/agents",
                new AgentRequest("+260966000001", "Mwila Again"), Void.class).status()).isEqualTo(409);
        assertThat(api().get(council.adminToken(), base(council) + "/agents", AgentBody[].class).body()).hasSize(1);
    }

    @Test
    @DisplayName("an officer can list traders and agents but cannot register anyone")
    void officerIsReadOnly() {
        Fixture council = newCouncil();
        String officer = council.officerToken();

        api().post(council.adminToken(), base(council) + "/traders", new TraderRequest("0977555666", "Masked", "en"), Void.class);
        api().post(council.adminToken(), base(council) + "/agents", new AgentRequest("0966555777", "Masked"), Void.class);
        for (String list : List.of("/traders", "/agents")) {
            String full = api().get(council.adminToken(), base(council) + list, String.class).body();
            String masked = api().get(officer, base(council) + list, String.class).body();
            assertThat(full).as(list).contains("555");
            assertThat(masked).as(list + " for an officer").contains("***").doesNotContain("555");
        }
        assertThat(api().get(officer, base(council) + "/traders", Void.class).status()).isEqualTo(200);
        assertThat(api().get(officer, base(council) + "/agents", Void.class).status()).isEqualTo(200);
        assertThat(api().post(officer, base(council) + "/traders", new TraderRequest("0977000739", "Mary", "en"),
                Void.class).status()).isEqualTo(403);
        assertThat(api().post(officer, base(council) + "/agents", new AgentRequest("0966000001", "Mwila"),
                Void.class).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("the database itself refuses a phone number that is not E.164")
    void databaseRefusesUnnormalisedNumbers() {
        Fixture council = newCouncil();

        for (String bad : new String[] {"0977000739", "260977000739", "+27821234567", "+260977"}) {
            assertThatThrownBy(() -> tenant.inCouncil(council.councilId(), () -> jdbc
                    .sql("insert into trader (id, council_id, msisdn, display_name) values (:id, :c, :m, 'X Trader')")
                    .param("id", UUID.randomUUID()).param("c", council.councilId()).param("m", bad).update()))
                    .as("trader " + bad).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> tenant.inCouncil(council.councilId(), () -> jdbc
                    .sql("insert into agent (id, council_id, msisdn, display_name, created_at) values (:id, :c, :m, 'X Agent', now())")
                    .param("id", UUID.randomUUID()).param("c", council.councilId()).param("m", bad).update()))
                    .as("agent " + bad).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    @DisplayName("the full phone number never appears in any log line, only the masked one")
    void phoneNumbersAreMaskedInLogs() {
        Fixture council = newCouncil();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            api().post(council.adminToken(), base(council) + "/traders",
                    new TraderRequest("0977 000 739", "Mary Banda", "en"), Void.class);
            api().post(council.adminToken(), base(council) + "/agents",
                    new AgentRequest("0966 000 654", "Mwila Chanda"), Void.class);
            api().post(council.adminToken(), base(council) + "/traders",
                    new TraderRequest("0977000739", "Duplicate", "en"), Void.class);
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }

        // Only what the SERVER logged. The test thread itself runs the HTTP client, which logs the request
        // it sends (including the number); that is the test talking, not the application.
        String testThread = Thread.currentThread().getName();
        List<String> lines = appender.list.stream()
                .filter(e -> !e.getThreadName().equals(testThread))
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).as("the server logged plenty while handling the requests").hasSizeGreaterThan(5);
        assertThat(lines).as("registering people logged something").anyMatch(l -> l.contains("0977***739"));
        assertThat(lines).anyMatch(l -> l.contains("0966***654"));
        assertThat(lines).as("no log line has the middle digits of either number")
                .noneMatch(l -> l.contains("000739") || l.contains("000 739") || l.contains("000654")
                        || l.contains("000 654"));
    }

    @Test
    @DisplayName("the audit log records the id of a registered trader, never their phone number")
    void auditNeverHoldsPhoneNumbers() {
        Fixture council = newCouncil();
        TraderBody trader = api().post(council.adminToken(), base(council) + "/traders",
                new TraderRequest("0977555000", "Mary Banda", "en"), TraderBody.class).body();

        List<String> targets = tenant.inCouncil(council.councilId(), () -> jdbc
                .sql("select target from audit_log where action = 'trader.register'")
                .query(String.class).list());

        assertThat(targets).containsExactly(trader.id().toString());
        assertThat(targets.get(0)).doesNotContain("555000");
    }

    // ---------------------------------------------------------------- plates

    /** Loads the PDF the API returned, failing the test if it is not a PDF. */
    private PDDocument load(byte[] pdf) throws Exception {
        return Loader.loadPDF(pdf);
    }

    @Test
    @DisplayName("a market's plates download as one A6 PDF with one page per stall, each with its code and a QR code")
    void platesDownloadAsOnePdf() throws Exception {
        Fixture council = newCouncil();
        MarketBody market = marketWithStalls(council, "SOW", "Soweto Market",
                "section,number,class\nB,114,STANDARD\nB,115,STANDARD\nC,7,LARGE\n");
        List<StallBody> stalls = List.of(api().get(council.adminToken(),
                base(council) + "/markets/" + market.id() + "/stalls", StallBody[].class).body());

        Result<byte[]> response = api().get(council.adminToken(),
                base(council) + "/markets/" + market.id() + "/plates.pdf", byte[].class);

        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), 0, 5)).isEqualTo("%PDF-");
        try (PDDocument pdf = load(response.body())) {
            assertThat(pdf.getNumberOfPages()).as("one page per stall").isEqualTo(3);
            PDFRenderer renderer = new PDFRenderer(pdf);
            PDFTextStripper text = new PDFTextStripper();
            for (int page = 0; page < 3; page++) {
                final int pageNumber = page + 1;
                PDRectangle size = pdf.getPage(page).getMediaBox();
                assertThat(size.getWidth()).as("A6 width").isEqualTo(PDRectangle.A6.getWidth());
                assertThat(size.getHeight()).as("A6 height").isEqualTo(PDRectangle.A6.getHeight());

                text.setStartPage(pageNumber);
                text.setEndPage(pageNumber);
                String printed = text.getText(pdf);
                StallBody onThisPage = stalls.stream().filter(s -> printed.contains(s.code())).findFirst()
                        .orElseThrow(() -> new AssertionError("no stall code printed on page " + pageNumber));
                assertThat(printed).contains("Soweto Market");

                QrTestSupport.assertPrintedCodeReads(renderer, page,
                        "msika://stall/" + onThisPage.code() + "?t=" + onThisPage.qrToken());
            }
        }
    }

    @Test
    @DisplayName("a market of 300 stalls makes one PDF of 300 pages, and the first, middle and last QR codes read back")
    void aWholeMarketOfPlates() throws Exception {
        Fixture council = newCouncil();
        StringBuilder csv = new StringBuilder("section,number,class\n");
        for (int i = 1; i <= 300; i++) {
            csv.append("A,").append(i).append(",STANDARD\n");
        }
        MarketBody market = marketWithStalls(council, "BIG", "Big Market", csv.toString());
        List<StallBody> stalls = List.of(api().get(council.adminToken(),
                base(council) + "/markets/" + market.id() + "/stalls", StallBody[].class).body());

        Result<byte[]> response = api().get(council.adminToken(),
                base(council) + "/markets/" + market.id() + "/plates.pdf", byte[].class);

        assertThat(response.status()).isEqualTo(200);
        try (PDDocument pdf = load(response.body())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(300);
            PDFRenderer renderer = new PDFRenderer(pdf);
            PDFTextStripper text = new PDFTextStripper();
            for (int page : new int[] {0, 149, 299}) {
                text.setStartPage(page + 1);
                text.setEndPage(page + 1);
                String printed = text.getText(pdf);
                // The stall code printed on this page tells us which stall's token its QR code must hold.
                StallBody onThisPage = stalls.stream().filter(s -> printed.contains(s.code() + "\n")
                        || printed.contains(s.code() + "\r")).findFirst()
                        .orElseThrow(() -> new AssertionError("no stall code printed on the page"));
                QrTestSupport.assertPrintedCodeReads(renderer, page,
                        "msika://stall/" + onThisPage.code() + "?t=" + onThisPage.qrToken());
            }
        }
    }

    @Test
    @DisplayName("the plates need sign-in and the right council; an officer can print them; an empty market has none")
    void platesAccess() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();
        MarketBody market = marketWithStalls(a, "SOW", "Soweto Market", "section,number,class\nB,1,STANDARD\n");
        MarketBody emptyMarket = marketWithStalls(a, "EMP", "Empty Market", null);
        String path = base(a) + "/markets/" + market.id() + "/plates.pdf";

        assertThat(api().get(null, path, Void.class).status()).as("not signed in").isEqualTo(401);
        assertThat(api().get(b.adminToken(), path, Void.class).status()).as("another council").isEqualTo(403);
        assertThat(api().get(a.officerToken(), path, Void.class).status()).as("an officer may print").isEqualTo(200);
        assertThat(api().get(a.adminToken(), base(a) + "/markets/" + emptyMarket.id() + "/plates.pdf", Void.class)
                .status()).as("no stalls yet").isEqualTo(409);
        assertThat(api().get(a.adminToken(), base(a) + "/markets/" + UUID.randomUUID() + "/plates.pdf", Void.class)
                .status()).as("unknown market").isEqualTo(404);
    }

    @Test
    @DisplayName("the response is a PDF download")
    void responseHeaders() {
        Fixture council = newCouncil();
        MarketBody market = marketWithStalls(council, "SOW", "Soweto Market", "section,number,class\nB,1,STANDARD\n");

        org.springframework.web.client.RestClient client =
                org.springframework.web.client.RestClient.create("http://localhost:" + port);
        var entity = client.get().uri(base(council) + "/markets/" + market.id() + "/plates.pdf")
                .headers(h -> h.setBearerAuth(council.adminToken())).retrieve().toEntity(byte[].class);

        assertThat(entity.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(entity.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("stall-plates.pdf");
    }
}
