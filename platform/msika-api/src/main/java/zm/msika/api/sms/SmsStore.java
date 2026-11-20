package zm.msika.api.sms;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.sms.SmsRouting;

/**
 * The SMS tables (migration V10). Every method runs inside a transaction opened by the caller as the right tenant:
 * a council, or the unassigned messages (see {@code TenantContext.inSmsTenant}).
 */
@Repository
public class SmsStore {

    /** A received message waiting to be processed. */
    public record Inbound(UUID id, UUID councilId, String gatewayMessageId, String fromMsisdn, String body,
            Instant receivedAt, Instant storedAt) {}

    /** A message waiting to be sent. */
    public record Outbound(UUID id, UUID councilId, String toMsisdn, String body, String template, int attempts) {}

    private final JdbcClient jdbc;

    SmsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    /** Every council's record of a phone number, through the narrow lookup in migration V10. */
    public List<SmsRouting.Sender> findSenders(String msisdn) {
        return jdbc.sql("select council_id, trader_id, agent_id from sms_find_sender(:m)")
                .param("m", msisdn)
                .query((rs, n) -> new SmsRouting.Sender(rs.getObject("council_id", UUID.class),
                        rs.getObject("trader_id", UUID.class), rs.getObject("agent_id", UUID.class)))
                .list();
    }

    /**
     * Stores a received message, unless one with the same gateway id is already stored (in any council: the unique
     * index sees every row, whatever row-level security hides).
     *
     * @return true if stored now, false if it was a repeat
     */
    public boolean insertInbound(Inbound message) {
        return jdbc.sql("insert into sms_inbound (id, council_id, gateway_message_id, from_msisdn, body, received_at, "
                        + "stored_at, status) values (:id, :c, :g, :from, :body, :received, :stored, 'RECEIVED') "
                        + "on conflict (gateway_message_id) do nothing")
                .param("id", message.id()).param("c", message.councilId()).param("g", message.gatewayMessageId())
                .param("from", message.fromMsisdn()).param("body", message.body())
                .param("received", utc(message.receivedAt())).param("stored", utc(message.storedAt()))
                .update() == 1;
    }

    /** Locks a message that is still waiting, so two workers cannot process it twice. */
    public Optional<Inbound> claimInbound(UUID id) {
        return jdbc.sql("select id, council_id, gateway_message_id, from_msisdn, body, received_at, stored_at "
                        + "from sms_inbound where id = :id and status = 'RECEIVED' for update skip locked")
                .param("id", id)
                .query((rs, n) -> new Inbound(rs.getObject("id", UUID.class), rs.getObject("council_id", UUID.class),
                        rs.getString("gateway_message_id"), rs.getString("from_msisdn"), rs.getString("body"),
                        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("stored_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Messages still waiting that were stored before the given time: left behind by a crash or a busy moment. */
    public List<UUID> waitingSince(Instant before, int limit) {
        return jdbc.sql("select id from sms_inbound where status = 'RECEIVED' and stored_at < :before "
                        + "order by stored_at limit :limit")
                .param("before", utc(before)).param("limit", limit)
                .query(UUID.class).list();
    }

    /** How many messages this number sent that were stored in the window, the given one included. */
    public int countFrom(String msisdn, Instant since, Instant until) {
        return jdbc.sql("select count(*) from sms_inbound where from_msisdn = :m and stored_at >= :since and stored_at <= :until")
                .param("m", msisdn).param("since", utc(since)).param("until", utc(until))
                .query(Integer.class).single();
    }

    public void finishInbound(UUID id, String status, String outcome, Instant at) {
        jdbc.sql("update sms_inbound set status = :s, outcome = :o, processed_at = :at where id = :id")
                .param("s", status).param("o", outcome).param("at", utc(at)).param("id", id).update();
    }

    public void enqueue(UUID councilId, String toMsisdn, String body, String template, UUID inReplyTo, Instant now) {
        jdbc.sql("insert into sms_outbound (id, council_id, to_msisdn, body, template, in_reply_to, status, attempts, "
                        + "next_attempt_at, created_at) values (:id, :c, :to, :body, :t, :reply, 'PENDING', 0, :now, :now)")
                .param("id", UUID.randomUUID()).param("c", councilId).param("to", toMsisdn).param("body", body)
                .param("t", template).param("reply", inReplyTo).param("now", utc(now))
                .update();
    }

    /** Locks up to {@code limit} messages that are due to be sent. */
    public List<Outbound> claimDue(Instant now, int limit) {
        return jdbc.sql("select id, council_id, to_msisdn, body, template, attempts from sms_outbound "
                        + "where status = 'PENDING' and next_attempt_at <= :now order by next_attempt_at limit :limit "
                        + "for update skip locked")
                .param("now", utc(now)).param("limit", limit)
                .query((rs, n) -> new Outbound(rs.getObject("id", UUID.class), rs.getObject("council_id", UUID.class),
                        rs.getString("to_msisdn"), rs.getString("body"), rs.getString("template"), rs.getInt("attempts")))
                .list();
    }

    public void markSent(UUID id, String gatewayReference, Instant at) {
        jdbc.sql("update sms_outbound set status = 'SENT', attempts = attempts + 1, sent_at = :at, "
                        + "gateway_reference = :ref, last_error = null where id = :id")
                .param("at", utc(at)).param("ref", gatewayReference).param("id", id).update();
    }

    public void markRetry(UUID id, String error, Instant nextAttempt) {
        jdbc.sql("update sms_outbound set attempts = attempts + 1, last_error = :e, next_attempt_at = :next where id = :id")
                .param("e", truncate(error)).param("next", utc(nextAttempt)).param("id", id).update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("update sms_outbound set status = 'FAILED', attempts = attempts + 1, last_error = :e where id = :id")
                .param("e", truncate(error)).param("id", id).update();
    }

    private static String truncate(String error) {
        String e = error == null ? "unknown error" : error;
        return e.length() > 300 ? e.substring(0, 300) : e;
    }
}
