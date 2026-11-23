package zm.msika.elevysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Simulated eLevy (card M-22): accounts, top-ups, the daily charge and arrears, through the HTTP API. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LevyApiTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../infra/postgres/init/01-init.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-init.sh")
            .withEnv("MSIKA_OWNER_PASSWORD", "test_owner")
            .withEnv("MSIKA_APP_PASSWORD", "test_app")
            .withEnv("ZAMSIM_PASSWORD", "test_zamsim")
            .withEnv("ELEVYSIM_PASSWORD", "test_elevysim");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/elevysim";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.password", () -> "test_elevysim");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "elevysim");
        registry.add("spring.flyway.password", () -> "test_elevysim");
        registry.add("elevysim.daily-charge.enabled", () -> "false");
    }

    record Account(UUID id, String councilRef, String marketRef, String traderRef, long dailyLevyNgwee,
            long balanceNgwee, long arrearsNgwee, LocalDate lastChargedOn, LocalDate paidTo) {}

    record Movement(long id, String kind, long amountNgwee, long balanceAfter, long arrearsAfter, LocalDate chargeDate) {}

    record Run(LocalDate date, int charged, int alreadyCharged, long totalNgwee) {}

    record Result<T>(int status, T body) {}

    record Open(String councilRef, String marketRef, String traderRef, Long dailyLevyNgwee) {}

    record TopUp(Long amountNgwee, String reference) {}

    record Charge(LocalDate date) {}

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private <T> Result<T> send(HttpMethod method, String path, String key, Object body, Class<T> type) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> new Result<>(res.getStatusCode().value(), res.bodyTo(type)));
    }

    /** A new account with a K5 daily levy. Each test uses its own market, so tests never see each other's accounts. */
    private Account open(long levy) {
        Result<Account> r = send(HttpMethod.POST, "/v1/levy-accounts", null,
                new Open("council-1", "market-" + UUID.randomUUID(), "trader-1", levy), Account.class);
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private Account get(Account a) {
        return send(HttpMethod.GET, "/v1/levy-accounts/" + a.id(), null, null, Account.class).body();
    }

    private Account topUp(Account a, String key, long ngwee) {
        Result<Account> r = send(HttpMethod.POST, "/v1/levy-accounts/" + a.id() + "/top-ups", key, new TopUp(ngwee, "test"),
                Account.class);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private Run charge(LocalDate date) {
        return send(HttpMethod.POST, "/v1/admin/run-daily-charge", null, new Charge(date), Run.class).body();
    }

    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    @Test
    @DisplayName("a charge with enough balance takes one day's levy from it and leaves no arrears")
    void chargeWithBalance() {
        Account a = open(500);
        topUp(a, "k1", 1_200);

        charge(DAY);

        Account after = get(a);
        assertThat(after.balanceNgwee()).isEqualTo(700);
        assertThat(after.arrearsNgwee()).isZero();
        assertThat(after.lastChargedOn()).isEqualTo(DAY);
        assertThat(after.paidTo()).as("K7 covers one more whole day").isEqualTo(DAY.plusDays(1));
    }

    @Test
    @DisplayName("a charge the balance cannot cover takes what there is and the rest becomes arrears")
    void chargeIntoArrears() {
        Account a = open(500);
        topUp(a, "k1", 200);

        charge(DAY);
        charge(DAY.plusDays(1));

        Account after = get(a);
        assertThat(after.balanceNgwee()).isZero();
        assertThat(after.arrearsNgwee()).isEqualTo(800);
        assertThat(after.paidTo()).as("two days are unpaid").isEqualTo(DAY.minusDays(1));
        List<Movement> moves = List.of(send(HttpMethod.GET, "/v1/levy-accounts/" + a.id() + "/movements", null, null,
                Movement[].class).body());
        assertThat(moves).extracting(Movement::kind, Movement::balanceAfter, Movement::arrearsAfter)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("TOP_UP", 200L, 0L),
                        org.assertj.core.groups.Tuple.tuple("CHARGE", 0L, 300L),
                        org.assertj.core.groups.Tuple.tuple("CHARGE", 0L, 800L));
    }

    @Test
    @DisplayName("a top-up clears arrears first and only the rest goes to the balance")
    void topUpClearsArrears() {
        Account a = open(500);
        charge(DAY);
        charge(DAY.plusDays(1));
        assertThat(get(a).arrearsNgwee()).isEqualTo(1_000);

        Account partly = topUp(a, "k1", 400);
        assertThat(partly.arrearsNgwee()).isEqualTo(600);
        assertThat(partly.balanceNgwee()).isZero();

        Account cleared = topUp(a, "k2", 1_000);
        assertThat(cleared.arrearsNgwee()).isZero();
        assertThat(cleared.balanceNgwee()).isEqualTo(400);
    }

    @Test
    @DisplayName("the same top-up key twice is one top-up; the same key with another amount is refused")
    void topUpIdempotent() {
        Account a = open(500);
        topUp(a, "same", 1_000);
        topUp(a, "same", 1_000);

        assertThat(get(a).balanceNgwee()).isEqualTo(1_000);
        assertThat(send(HttpMethod.POST, "/v1/levy-accounts/" + a.id() + "/top-ups", "same", new TopUp(999L, null),
                String.class).status()).isEqualTo(409);
        assertThat(send(HttpMethod.POST, "/v1/levy-accounts/" + a.id() + "/top-ups", null, new TopUp(10L, null),
                String.class).status()).isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/levy-accounts/" + a.id() + "/top-ups", "zero", new TopUp(0L, null),
                String.class).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the daily charge runs at most once per account per day, however often it is called")
    void chargeOncePerDay() {
        Account a = open(500);
        topUp(a, "k", 5_000);

        Run first = charge(DAY);
        Run second = charge(DAY);

        assertThat(first.charged()).isGreaterThanOrEqualTo(1);
        assertThat(second.alreadyCharged()).isGreaterThanOrEqualTo(1);
        assertThat(get(a).balanceNgwee()).isEqualTo(4_500);
    }

    @Test
    @DisplayName("charges and top-ups arriving together are applied one after the other: nothing is lost")
    void concurrent() throws Exception {
        Account a = open(100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            jobs.add(pool.submit(() -> topUp(a, "c" + n, 150)));
            jobs.add(pool.submit(() -> charge(DAY.plusDays(n))));
        }
        for (Future<?> f : jobs) {
            f.get();
        }
        pool.shutdown();

        // 20 top-ups of K1.50 and 20 charges of K1: K10 left, whatever the order.
        Account after = get(a);
        assertThat(after.balanceNgwee() - after.arrearsNgwee()).isEqualTo(20 * 150 - 20 * 100);
    }

    @Test
    @DisplayName("bad requests are 400, an unknown account is 404, and a second account for the same trader is 409")
    void validation() {
        Account a = open(500);
        assertThat(send(HttpMethod.POST, "/v1/levy-accounts", null, new Open("c", a.marketRef(), "trader-1", 500L),
                String.class).status()).isEqualTo(409);
        assertThat(send(HttpMethod.POST, "/v1/levy-accounts", null, new Open("c", "m", "t", 0L), String.class).status())
                .isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/v1/levy-accounts", null, new Open("", "m", "t", 500L), String.class).status())
                .isEqualTo(400);
        assertThat(send(HttpMethod.GET, "/v1/levy-accounts/" + UUID.randomUUID(), null, null, String.class).status())
                .isEqualTo(404);
        Account[] found = send(HttpMethod.GET, "/v1/levy-accounts?market=" + a.marketRef() + "&trader=trader-1", null, null,
                Account[].class).body();
        assertThat(found).extracting(Account::id).containsExactly(a.id());
    }

    @Test
    @DisplayName("the movements are append-only at the database")
    void appendOnly() {
        Account a = open(500);
        topUp(a, "k", 100);
        assertThatThrownBy(() -> jdbc.sql("update levy_movement set amount_ngwee = 1 where account_id = :id")
                .param("id", a.id()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from levy_movement where account_id = :id")
                .param("id", a.id()).update()).hasMessageContaining("append-only");
    }
}
