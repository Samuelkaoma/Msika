package zm.msika.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import zm.msika.api.ApiClient.Result;
import zm.msika.api.ApiTest;
import zm.msika.api.seal.SealKeys;
import zm.msika.core.ledger.NewEntry;
import zm.msika.ledger.Currency;
import zm.msika.ledger.DailySeal;
import zm.msika.ledger.EcdsaSignatureScheme;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/** The seal endpoints and day close by hand, through the real HTTP API with real sign-in and roles. */
class SealApiTest extends ApiTest {

    record SealBody(UUID councilId, String businessDate, int entryCount, String merkleRoot, String previousSealHash,
            String sealHash, String algorithm, String signature, String keyId, String sealedAt) {}

    record SealKeyBody(String keyId, String algorithm, String publicKey) {}

    record DayCloseRequest(String through) {}

    record DayCloseBody(List<String> sealed) {}

    record ErrorBody(String error, String message) {}

    record MarketRequest(String code, String name, String location) {}

    record MarketBody(UUID id) {}

    record StallBody(UUID id) {}

    @Autowired
    LedgerService ledger;

    @Autowired
    SealKeys keys;

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Lusaka"));
    }

    private static String path(Fixture council, String rest) {
        return "/api/v1/councils/" + council.councilId() + rest;
    }

    /** Gives the council a stall and records two deposits today, so there is something to seal. */
    private void recordTwoDeposits(Fixture council) {
        MarketBody market = api().post(council.adminToken(), path(council, "/markets"),
                new MarketRequest("SEA", "Seal Market", ""), MarketBody.class).body();
        api().postCsv(council.adminToken(), path(council, "/markets/" + market.id() + "/stalls/import"),
                "section,number,class\nA,1,STANDARD\n", Void.class);
        UUID stall = api().get(council.adminToken(), path(council, "/markets/" + market.id() + "/stalls"),
                StallBody[].class).body()[0].id();
        for (int i = 1; i <= 2; i++) {
            ledger.record(new NewEntry(council.councilId(), stall, EntryKind.DEPOSIT, Provenance.DEPOSITED,
                    Money.ofMinor(10_000L * i, Currency.ZMW), Instant.now(), "api-dep-" + i, null));
        }
    }

    private Result<DayCloseBody> closeToday(Fixture council, String token) {
        return api().post(token, path(council, "/day-close"), new DayCloseRequest(today().toString()),
                DayCloseBody.class);
    }

    @Test
    @DisplayName("an operator closes a day by hand; closing it again is safe and seals nothing")
    void operatorClosesDays() {
        Fixture council = newCouncil();
        recordTwoDeposits(council);

        Result<DayCloseBody> first = closeToday(council, operatorToken());
        Result<DayCloseBody> second = closeToday(council, operatorToken());

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().sealed()).containsExactly(today().toString());
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().sealed()).as("already sealed, so nothing to do").isEmpty();
    }

    @Test
    @DisplayName("only an operator may close days: an admin, an officer and a stranger are refused")
    void onlyOperatorsCloseDays() {
        Fixture council = newCouncil();

        assertThat(closeToday(council, council.adminToken()).status()).isEqualTo(403);
        assertThat(closeToday(council, council.officerToken()).status()).isEqualTo(403);
        assertThat(closeToday(council, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a day that has not started yet cannot be closed, and the date is required")
    void badDayCloseRequests() {
        Fixture council = newCouncil();

        assertThat(api().post(operatorToken(), path(council, "/day-close"),
                new DayCloseRequest(today().plusDays(1).toString()), Void.class).status()).isEqualTo(400);
        assertThat(api().post(operatorToken(), path(council, "/day-close"), new DayCloseRequest(null), Void.class)
                .status()).isEqualTo(400);
        assertThat(api().post(operatorToken(), "/api/v1/councils/" + UUID.randomUUID() + "/day-close",
                new DayCloseRequest(today().toString()), Void.class).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /api/v1/seals/{date} returns the signed-in user's council's seal, for an admin and an officer")
    void readOwnSeal() {
        Fixture council = newCouncil();
        recordTwoDeposits(council);
        closeToday(council, operatorToken());

        for (String token : List.of(council.adminToken(), council.officerToken())) {
            Result<SealBody> seal = api().get(token, "/api/v1/seals/" + today(), SealBody.class);

            assertThat(seal.status()).isEqualTo(200);
            assertThat(seal.body().councilId()).isEqualTo(council.councilId());
            assertThat(seal.body().businessDate()).isEqualTo(today().toString());
            assertThat(seal.body().entryCount()).isEqualTo(2);
            assertThat(seal.body().keyId()).isEqualTo(keys.keyId());
            assertThat(seal.body().merkleRoot()).hasSize(64);
        }
    }

    @Test
    @DisplayName("anyone holding the public key can check a seal they were given")
    void aSealCheckedWithThePublicKey() throws Exception {
        Fixture council = newCouncil();
        recordTwoDeposits(council);
        closeToday(council, operatorToken());

        SealKeyBody key = api().get(null, "/api/v1/seal-key", SealKeyBody.class).body();
        SealBody body = api().get(council.adminToken(), "/api/v1/seals/" + today(), SealBody.class).body();
        HexFormat hex = HexFormat.of();
        DailySeal seal = new DailySeal(body.councilId(), LocalDate.parse(body.businessDate()), body.entryCount(),
                hex.parseHex(body.merkleRoot()), hex.parseHex(body.previousSealHash()), body.algorithm(),
                Base64.getDecoder().decode(body.signature()));

        boolean signatureOk = new EcdsaSignatureScheme().verify(seal.signedBytes(), seal.signature(),
                zm.msika.api.seal.SealKeys.publicKeyOf(key.publicKey()));

        assertThat(signatureOk).as("the signature in the response verifies against the published key").isTrue();
        assertThat(hex.formatHex(seal.sealHash())).as("and the seal hash is what the next day will link to")
                .isEqualTo(body.sealHash());
        assertThat(key.algorithm()).isEqualTo("SHA256withECDSA");
    }

    @Test
    @DisplayName("the public key needs no sign-in, but a seal does")
    void publicKeyIsPublic() {
        Fixture council = newCouncil();

        assertThat(api().get(null, "/api/v1/seal-key", Void.class).status()).isEqualTo(200);
        assertThat(api().get(null, "/api/v1/seals/" + today(), Void.class).status()).isEqualTo(401);
        assertThat(api().get(null, path(council, "/seals/" + today()), Void.class).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("an unsealed day is 404, a bad date is 400, and an operator must use the council path")
    void missingAndBadSeals() {
        Fixture council = newCouncil();

        assertThat(api().get(council.adminToken(), "/api/v1/seals/" + today().minusDays(400), Void.class).status())
                .isEqualTo(404);
        assertThat(api().get(council.adminToken(), "/api/v1/seals/not-a-date", Void.class).status()).isEqualTo(400);
        assertThat(api().get(operatorToken(), "/api/v1/seals/" + today(), Void.class).status())
                .as("an operator has no council of their own: they use the council path").isEqualTo(403);
    }

    @Test
    @DisplayName("a council cannot read another council's seal, but an operator can read any")
    void sealsAreScopedToTheirCouncil() {
        Fixture a = newCouncil();
        Fixture b = newCouncil();
        recordTwoDeposits(a);
        closeToday(a, operatorToken());

        assertThat(api().get(b.adminToken(), path(a, "/seals/" + today()), Void.class).status()).isEqualTo(403);
        assertThat(api().get(b.adminToken(), "/api/v1/seals/" + today(), Void.class).status())
                .as("b's own seal for that day does not exist").isEqualTo(404);
        assertThat(api().get(operatorToken(), path(a, "/seals/" + today()), SealBody.class).status()).isEqualTo(200);
    }
}
