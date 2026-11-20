package zm.msika.api.payments;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.tenancy.TenantContext;

/** Builds a council with one stall directly in the database, for the payment tests. */
final class PaymentTestSupport {

    record Stall(UUID councilId, UUID stallId) {}

    private PaymentTestSupport() {}

    static Stall newStall(JdbcClient jdbc, TenantContext tenant) {
        UUID council = UUID.randomUUID();
        UUID market = UUID.randomUUID();
        UUID stall = UUID.randomUUID();
        // Created long ago, so the day the money arrives is an open day.
        jdbc.sql("insert into council (id, name, timezone, created_at) values (:id, :name, 'Africa/Lusaka', :at)")
                .param("id", council)
                .param("name", "Payment Test " + council)
                .param("at", OffsetDateTime.ofInstant(Instant.now().minusSeconds(3L * 86400), ZoneOffset.UTC))
                .update();
        tenant.inCouncil(council, () -> {
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :c, 'PAY', 'Payment Market', '20:00', now())")
                    .param("id", market).param("c", council).update();
            jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                            + "values (:id, :c, :m, 'PAY-A-1', 'STANDARD', 'VACANT', :token)")
                    .param("id", stall).param("c", council).param("m", market)
                    .param("token", UUID.randomUUID().toString().replace("-", "")).update();
        });
        return new Stall(council, stall);
    }
}
