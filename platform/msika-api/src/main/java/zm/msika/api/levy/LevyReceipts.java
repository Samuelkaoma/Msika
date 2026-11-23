package zm.msika.api.levy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zm.msika.api.sms.SmsStore;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.levy.LevyAuthorityPort;
import zm.msika.core.levy.LevyAuthorityUnavailableException;
import zm.msika.core.sms.SmsFormat;
import zm.msika.core.sms.SmsTemplate;

/**
 * The weekly levy receipt (card M-25): each trader whose levy was paid through Msika in the past seven days, and who
 * has not sent {@code STOP}, gets one text with the days paid, the amount, and the arrears the levy authority shows.
 * One receipt per trader per week, however often the job runs.
 */
@Component
public class LevyReceipts {

    private static final Logger LOG = LoggerFactory.getLogger(LevyReceipts.class);

    private final TenantContext tenant;
    private final JdbcClient jdbc;
    private final LevyAuthorityPort authority;
    private final SmsStore sms;
    private final Clock clock;

    LevyReceipts(TenantContext tenant, JdbcClient jdbc, LevyAuthorityPort authority, SmsStore sms, Clock clock) {
        this.tenant = tenant;
        this.jdbc = jdbc;
        this.authority = authority;
        this.sms = sms;
        this.clock = clock;
    }

    private record Due(UUID traderId, String msisdn, String stallCode, UUID marketId, LocalDate from, LocalDate to,
            long paidNgwee, long unpaidNgwee, String accountId) {}

    /** Sends the receipts for the week ending on {@code weekEnding}. Returns how many were queued. */
    public int send(UUID councilId, LocalDate weekEnding) {
        Instant now = clock.instant();
        OffsetDateTime since = OffsetDateTime.ofInstant(now.minus(7, ChronoUnit.DAYS), ZoneOffset.UTC);
        List<Due> due = tenant.inCouncil(councilId, () -> jdbc.sql("""
                        select i.trader_id, t.msisdn, min(s.code) as code, min(s.market_id::text) as market,
                               min(l.business_date) as from_day, max(l.business_date) as to_day,
                               sum(l.amount_ngwee) as paid,
                               (select coalesce(sum(u.amount_ngwee), 0) from levy_charge u
                                 where u.council_id = i.council_id and u.trader_id = i.trader_id
                                   and u.settled_by_instruction_id is null) as unpaid,
                               (select k.account_id from levy_account_link k where k.council_id = i.council_id
                                   and k.trader_id = i.trader_id and k.market_id = min(s.market_id::text)::uuid) as account
                        from payment_instruction i
                        join levy_charge l on l.settled_by_instruction_id = i.id
                        join trader t on t.id = i.trader_id
                        join stall s on s.id = i.stall_id
                        where i.council_id = :c and i.status = 'DONE' and i.updated_at > :since and t.daily_summaries
                          and not exists (select 1 from levy_receipt r where r.council_id = i.council_id
                                            and r.trader_id = i.trader_id and r.week_ending = :week)
                        group by i.trader_id, t.msisdn, i.council_id
                        """)
                .param("c", councilId).param("since", since).param("week", weekEnding)
                .query((rs, n) -> new Due(rs.getObject("trader_id", UUID.class), rs.getString("msisdn"), rs.getString("code"),
                        UUID.fromString(rs.getString("market")), rs.getObject("from_day", LocalDate.class),
                        rs.getObject("to_day", LocalDate.class), rs.getLong("paid"), rs.getLong("unpaid"),
                        rs.getString("account")))
                .list());
        List<String[]> texts = new ArrayList<>();
        for (Due d : due) {
            // The authority's arrears when it answers (that is the figure the council enforces); Msika's own otherwise.
            long arrears = d.unpaidNgwee();
            if (d.accountId() != null) {
                try {
                    Optional<LevyAuthorityPort.LevyAccount> a = authority.account(d.accountId());
                    if (a.isPresent()) {
                        arrears = a.get().arrearsNgwee();
                    }
                } catch (LevyAuthorityUnavailableException e) {
                    LOG.warn("levy authority not answering; receipt for trader {} uses Msika's figures", d.traderId());
                }
            }
            String body = SmsTemplate.LEVY_RECEIPT.render("FROMDATE", SmsFormat.day(d.from()), "DATE", SmsFormat.day(d.to()),
                    "STALL", d.stallCode(), "AMOUNT", SmsFormat.kwacha(d.paidNgwee()), "AMOUNT2", SmsFormat.kwacha(arrears));
            texts.add(new String[] {d.traderId().toString(), d.msisdn(), body});
        }
        return tenant.inCouncil(councilId, () -> {
            int queued = 0;
            for (String[] t : texts) {
                int claimed = jdbc.sql("insert into levy_receipt (council_id, trader_id, week_ending, sent_at) "
                                + "values (:c, :t, :w, :at) on conflict do nothing")
                        .param("c", councilId).param("t", UUID.fromString(t[0])).param("w", weekEnding)
                        .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update();
                if (claimed == 1) {
                    sms.enqueue(councilId, t[1], t[2], SmsTemplate.LEVY_RECEIPT.name(), null, now);
                    queued++;
                }
            }
            return queued;
        });
    }

    /** Sunday evening in Lusaka: the week's receipts for every council. */
    @Component
    @ConditionalOnProperty(name = "msika.levy-receipts.enabled", havingValue = "true", matchIfMissing = true)
    static class Weekly {
        private final LevyReceipts receipts;
        private final zm.msika.core.council.CouncilRepository councils;
        private final LevyChargeService levy;

        Weekly(LevyReceipts receipts, zm.msika.core.council.CouncilRepository councils, LevyChargeService levy) {
            this.receipts = receipts;
            this.councils = councils;
            this.levy = levy;
        }

        @Scheduled(cron = "${msika.levy-receipts.cron:0 0 18 * * SUN}", zone = "Africa/Lusaka")
        void run() {
            for (UUID council : councils.listIds()) {
                try {
                    receipts.send(council, levy.today(council));
                } catch (RuntimeException e) {
                    LOG.error("weekly levy receipts failed for council {}", council, e);
                }
            }
        }
    }
}
