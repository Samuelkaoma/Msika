package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.payments.PaymentRequest;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.ledger.Currency;
import zm.msika.ledger.Money;

/** Keeps payment requests in Postgres. Every query also filters by council, on top of the row-level security. */
@Repository
class JdbcPaymentRequestRepository implements PaymentRequestRepository {

    private static final String COLUMNS = "id, council_id, purpose, stall_id, amount_ngwee, status, "
            + "payments_transaction_id, failure_reason, created_at, completed_at";

    private final JdbcClient jdbc;

    JdbcPaymentRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(PaymentRequest r) {
        jdbc.sql("insert into payment_request (" + COLUMNS + ") values (:id, :councilId, :purpose, :stallId, :amount, "
                        + ":status, :txn, :reason, :createdAt, :completedAt)")
                .param("id", r.id())
                .param("councilId", r.councilId())
                .param("purpose", r.purpose().name())
                .param("stallId", r.stallId())
                .param("amount", r.amount().minorUnits())
                .param("status", r.status().name())
                .param("txn", r.paymentsTransactionId())
                .param("reason", r.failureReason())
                .param("createdAt", OffsetDateTime.ofInstant(r.createdAt(), ZoneOffset.UTC))
                .param("completedAt", r.completedAt() == null ? null : OffsetDateTime.ofInstant(r.completedAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<PaymentRequest> find(UUID councilId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from payment_request where council_id = :councilId and id = :id")
                .param("councilId", councilId).param("id", id)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<PaymentRequest> findForUpdate(UUID councilId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from payment_request where council_id = :councilId and id = :id for update")
                .param("councilId", councilId).param("id", id)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public void attachTransaction(UUID councilId, UUID id, String txn) {
        jdbc.sql("update payment_request set payments_transaction_id = :txn "
                        + "where council_id = :councilId and id = :id and payments_transaction_id is null")
                .param("txn", txn).param("councilId", councilId).param("id", id).update();
    }

    @Override
    public void markSucceeded(UUID councilId, UUID id, String txn, Instant at) {
        jdbc.sql("update payment_request set status = 'SUCCEEDED', payments_transaction_id = :txn, failure_reason = null, "
                        + "completed_at = :at where council_id = :councilId and id = :id and status = 'SENT'")
                .param("txn", txn).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("councilId", councilId).param("id", id).update();
    }

    @Override
    public void markFailed(UUID councilId, UUID id, String txn, String reason, Instant at) {
        jdbc.sql("update payment_request set status = 'FAILED', payments_transaction_id = :txn, failure_reason = :reason, "
                        + "completed_at = :at where council_id = :councilId and id = :id and status = 'SENT'")
                .param("txn", txn).param("reason", reason).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("councilId", councilId).param("id", id).update();
    }

    @Override
    public List<PaymentRequest> listSent(UUID councilId) {
        return jdbc.sql("select " + COLUMNS + " from payment_request where council_id = :councilId and status = 'SENT' "
                        + "order by created_at, id")
                .param("councilId", councilId)
                .query((rs, row) -> map(rs)).list();
    }

    private static PaymentRequest map(ResultSet rs) throws SQLException {
        OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
        return new PaymentRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                PaymentRequest.Purpose.valueOf(rs.getString("purpose")),
                rs.getObject("stall_id", UUID.class),
                Money.ofMinor(rs.getLong("amount_ngwee"), Currency.ZMW),
                PaymentRequest.Status.valueOf(rs.getString("status")),
                rs.getString("payments_transaction_id"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant());
    }
}
