package zm.msika.api.sms;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.ledger.EntryKind;

/**
 * The reads the SMS commands need, written as plain SQL. Every method runs in a transaction already set to the
 * right council, so row-level security keeps each query inside it.
 */
@Repository
class SmsQueries {

    record StallRef(UUID id, String code) {}

    record TraderInfo(UUID id, String msisdn, String displayName, Instant consentAt, boolean dailySummaries) {}

    record AgentInfo(UUID id, String msisdn, String displayName, boolean active) {}

    private final JdbcClient jdbc;

    SmsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<TraderInfo> trader(UUID councilId, UUID traderId) {
        return jdbc.sql("select id, msisdn, display_name, consent_levy_sweep_at, daily_summaries from trader "
                        + "where council_id = :c and id = :t")
                .param("c", councilId).param("t", traderId)
                .query((rs, n) -> new TraderInfo(rs.getObject("id", UUID.class), rs.getString("msisdn"),
                        rs.getString("display_name"),
                        Optional.ofNullable(rs.getObject("consent_levy_sweep_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant).orElse(null),
                        rs.getBoolean("daily_summaries")))
                .optional();
    }

    Optional<UUID> traderByPhone(UUID councilId, String msisdn) {
        return jdbc.sql("select id from trader where council_id = :c and msisdn = :m")
                .param("c", councilId).param("m", msisdn).query(UUID.class).optional();
    }

    Optional<AgentInfo> agent(UUID councilId, UUID agentId) {
        return jdbc.sql("select id, msisdn, display_name, active from agent where council_id = :c and id = :a")
                .param("c", councilId).param("a", agentId)
                .query((rs, n) -> new AgentInfo(rs.getObject("id", UUID.class), rs.getString("msisdn"),
                        rs.getString("display_name"), rs.getBoolean("active")))
                .optional();
    }

    /** The stalls the trader holds now, by code. */
    List<StallRef> stallsOf(UUID councilId, UUID traderId) {
        return jdbc.sql("select s.id, s.code from stall_allocation a join stall s on s.id = a.stall_id "
                        + "where a.council_id = :c and a.trader_id = :t and a.valid_to is null order by s.code")
                .param("c", councilId).param("t", traderId)
                .query((rs, n) -> new StallRef(rs.getObject("id", UUID.class), rs.getString("code")))
                .list();
    }

    /** The phone of whoever holds the stall now, if anyone. */
    Optional<String> holderPhone(UUID councilId, UUID stallId) {
        return jdbc.sql("select t.msisdn from stall_allocation a join trader t on t.id = a.trader_id "
                        + "where a.council_id = :c and a.stall_id = :s and a.valid_to is null")
                .param("c", councilId).param("s", stallId).query(String.class).optional();
    }

    UUID marketOf(UUID councilId, UUID stallId) {
        return jdbc.sql("select market_id from stall where council_id = :c and id = :s")
                .param("c", councilId).param("s", stallId).query(UUID.class).single();
    }

    Optional<String> stallCode(UUID councilId, UUID stallId) {
        return jdbc.sql("select code from stall where council_id = :c and id = :s")
                .param("c", councilId).param("s", stallId).query(String.class).optional();
    }

    /**
     * The stalls an agent may mean by what they typed: the exact code ({@code SOW-B-114}), or the short form on the
     * plate without the market ({@code B114} or {@code B-114}) matching the section and number of any market.
     */
    List<StallRef> stallsMatching(UUID councilId, String typed) {
        List<StallRef> exact = jdbc.sql("select id, code from stall where council_id = :c and code = :code")
                .param("c", councilId).param("code", typed)
                .query((rs, n) -> new StallRef(rs.getObject("id", UUID.class), rs.getString("code"))).list();
        if (!exact.isEmpty()) {
            return exact;
        }
        var m = java.util.regex.Pattern.compile("([A-Z0-9]*?[A-Z])-?([0-9]{1,5})").matcher(typed);
        if (!m.matches()) {
            return List.of();
        }
        String suffix = "-" + m.group(1) + "-" + m.group(2);
        return jdbc.sql("select id, code from stall where council_id = :c and right(code, :len) = :suffix order by code")
                .param("c", councilId).param("len", suffix.length()).param("suffix", suffix)
                .query((rs, n) -> new StallRef(rs.getObject("id", UUID.class), rs.getString("code"))).list();
    }

    /**
     * A stall's ledger totals for one business date, by kind. A reversal counts against the kind of the entry it
     * reverses, so a reversed deposit disappears from "deposited".
     */
    Map<EntryKind, Long> dayTotals(UUID councilId, UUID stallId, LocalDate date) {
        Map<EntryKind, Long> totals = new EnumMap<>(EntryKind.class);
        jdbc.sql("select coalesce(r.kind, e.kind) as k, sum(e.amount_ngwee) as total from ledger_entry e "
                        + "left join ledger_entry r on r.id = e.reverses_entry_id "
                        + "where e.council_id = :c and e.stall_id = :s and e.business_date = :d group by 1")
                .param("c", councilId).param("s", stallId).param("d", date)
                .query((rs, n) -> Map.entry(EntryKind.valueOf(rs.getString("k")), rs.getLong("total")))
                .list()
                .forEach(e -> totals.put(e.getKey(), e.getValue()));
        return totals;
    }

    /** An agent's confirmed deposits on a business date: how many, and how much. */
    long[] agentDay(UUID councilId, UUID agentId, LocalDate date) {
        return jdbc.sql("select count(*), coalesce(sum(amount_ngwee), 0) from deposit where council_id = :c "
                        + "and agent_id = :a and business_date = :d and status = 'CONFIRMED'")
                .param("c", councilId).param("a", agentId).param("d", date)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
    }

    void setDailySummaries(UUID councilId, UUID traderId, boolean on) {
        jdbc.sql("update trader set daily_summaries = :on where council_id = :c and id = :t")
                .param("on", on).param("c", councilId).param("t", traderId).update();
    }

    /** Records the trader's consent to the terms and the levy sweep, if not already given. True if given now. */
    boolean recordConsent(UUID councilId, UUID traderId, Instant at) {
        return jdbc.sql("update trader set consent_levy_sweep_at = :at, consent_data_at = coalesce(consent_data_at, :at) "
                        + "where council_id = :c and id = :t and consent_levy_sweep_at is null")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("c", councilId).param("t", traderId)
                .update() == 1;
    }

    /** Which councils have a stall with this exact code, through the narrow lookup in migration V11. */
    List<StallRef> findStallAnywhere(String code, java.util.function.Consumer<UUID> councilOut) {
        return jdbc.sql("select council_id, stall_id from sms_find_stall(:code)")
                .param("code", code)
                .query((rs, n) -> {
                    councilOut.accept(rs.getObject("council_id", UUID.class));
                    return new StallRef(rs.getObject("stall_id", UUID.class), code);
                })
                .list();
    }

    /** Raises a registration request, or finds the one already waiting. */
    void raiseRegistration(UUID councilId, UUID stallId, String msisdn, Instant at) {
        jdbc.sql("insert into registration_request (id, council_id, stall_id, msisdn, status, created_at) "
                        + "values (:id, :c, :s, :m, 'PENDING', :at) "
                        + "on conflict (council_id, stall_id, msisdn) where status = 'PENDING' do nothing")
                .param("id", UUID.randomUUID()).param("c", councilId).param("s", stallId).param("m", msisdn)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).update();
    }

    /** Stores a declared sale's reference; false if the reference is already used that day. */
    boolean addSaleReference(UUID councilId, long entryId, LocalDate date, String reference) {
        boolean usedByDeposit = jdbc.sql("select exists (select 1 from deposit where council_id = :c and business_date = :d "
                        + "and reference = :r)")
                .param("c", councilId).param("d", date).param("r", reference).query(Boolean.class).single();
        if (usedByDeposit) {
            return false;
        }
        return jdbc.sql("insert into declared_sale (ledger_entry_id, council_id, business_date, reference) "
                        + "values (:e, :c, :d, :r) on conflict do nothing")
                .param("e", entryId).param("c", councilId).param("d", date).param("r", reference).update() == 1;
    }

    Optional<String> saleReference(UUID councilId, long entryId) {
        return jdbc.sql("select reference from declared_sale where council_id = :c and ledger_entry_id = :e")
                .param("c", councilId).param("e", entryId).query(String.class).optional();
    }
}
