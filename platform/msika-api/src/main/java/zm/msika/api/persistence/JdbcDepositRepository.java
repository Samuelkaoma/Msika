package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.deposit.Deposit;
import zm.msika.core.deposit.DepositRepository;
import zm.msika.core.deposit.DepositStatus;
import zm.msika.ledger.Currency;
import zm.msika.ledger.Money;

/** Keeps deposits in Postgres. Every query also filters by council, on top of the row-level security. */
@Repository
class JdbcDepositRepository implements DepositRepository {

    private static final String COLUMNS = "id, council_id, stall_id, agent_id, amount_ngwee, status, reference, "
            + "business_date, payment_request_id, ledger_entry_id, status_reason, idempotency_key, created_at, updated_at";

    private final JdbcClient jdbc;

    JdbcDepositRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean add(Deposit d) {
        // ON CONFLICT DO NOTHING, not an error: a unique violation would abort the whole transaction, and the caller
        // still needs to read and write in it (see RequestDeposit).
        return jdbc.sql("insert into deposit (" + COLUMNS + ") values (:id, :councilId, :stallId, :agentId, :amount, :status, "
                        + ":reference, :businessDate, :requestId, :entryId, :reason, :key, :createdAt, :updatedAt) "
                        + "on conflict do nothing")
                .param("id", d.id())
                .param("councilId", d.councilId())
                .param("stallId", d.stallId())
                .param("agentId", d.agentId())
                .param("amount", d.amount().minorUnits())
                .param("status", d.status().name())
                .param("reference", d.reference())
                .param("businessDate", d.businessDate())
                .param("requestId", d.paymentRequestId())
                .param("entryId", d.ledgerEntryId())
                .param("reason", d.statusReason())
                .param("key", d.idempotencyKey())
                .param("createdAt", OffsetDateTime.ofInstant(d.createdAt(), ZoneOffset.UTC))
                .param("updatedAt", OffsetDateTime.ofInstant(d.updatedAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    @Override
    public Optional<Deposit> find(UUID councilId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from deposit where council_id = :councilId and id = :id")
                .param("councilId", councilId).param("id", id)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<Deposit> findByPaymentRequest(UUID councilId, UUID paymentRequestId) {
        return jdbc.sql("select " + COLUMNS + " from deposit where council_id = :councilId and payment_request_id = :requestId")
                .param("councilId", councilId).param("requestId", paymentRequestId)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<Deposit> findByKey(UUID councilId, UUID agentId, String key) {
        return jdbc.sql("select " + COLUMNS + " from deposit where council_id = :councilId and agent_id = :agentId "
                        + "and idempotency_key = :key")
                .param("councilId", councilId).param("agentId", agentId).param("key", key)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public List<Deposit> findByReference(UUID councilId, String reference, java.time.LocalDate since) {
        return jdbc.sql("select " + COLUMNS + " from deposit where council_id = :councilId and reference = :ref "
                        + "and business_date >= :since order by business_date desc, created_at desc")
                .param("councilId", councilId).param("ref", reference).param("since", since)
                .query((rs, row) -> map(rs)).list();
    }

    @Override
    public List<Deposit> list(UUID councilId, DepositStatus status, int limit) {
        return jdbc.sql("select " + COLUMNS + " from deposit where council_id = :councilId "
                        + "and (cast(:status as text) is null or status = :status) order by created_at desc, id limit :limit")
                .param("councilId", councilId)
                .param("status", status == null ? null : status.name())
                .param("limit", limit)
                .query((rs, row) -> map(rs)).list();
    }

    @Override
    public boolean transition(UUID councilId, UUID id, DepositStatus from, DepositStatus to, String reason,
            Long ledgerEntryId, Instant at) {
        // One UPDATE that checks the old status: when two changes arrive together, Postgres makes the second wait for
        // the first and then re-checks the status, so only one of them finds the row still in `from`.
        return jdbc.sql("update deposit set status = :to, status_reason = :reason, "
                        + "ledger_entry_id = coalesce(:entryId, ledger_entry_id), updated_at = :at "
                        + "where council_id = :councilId and id = :id and status = :from")
                .param("to", to.name()).param("reason", reason).param("entryId", ledgerEntryId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("councilId", councilId).param("id", id).param("from", from.name())
                .update() == 1;
    }

    private static Deposit map(ResultSet rs) throws SQLException {
        long entryId = rs.getLong("ledger_entry_id");
        Long ledgerEntryId = rs.wasNull() ? null : entryId;
        return new Deposit(
                rs.getObject("id", UUID.class),
                rs.getObject("council_id", UUID.class),
                rs.getObject("stall_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                Money.ofMinor(rs.getLong("amount_ngwee"), Currency.ZMW),
                DepositStatus.valueOf(rs.getString("status")),
                rs.getString("reference"),
                rs.getObject("business_date", LocalDate.class),
                rs.getObject("payment_request_id", UUID.class),
                ledgerEntryId,
                rs.getString("status_reason"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
