package zm.msika.api.sms;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.phone.PhoneNumber;

/**
 * The fake phones of the sim console (card M-20): the people Msika knows, a way to text Msika as any of them, and
 * each phone's conversation. Texts typed here go through exactly the same path as texts from the gateway.
 *
 * <p>For development and demos only: it exists only when {@code msika.sim-console.enabled=true}, and only a Msika
 * operator may use it (see the security rules). It never runs in a real pilot.
 */
@RestController
@ConditionalOnProperty(name = "msika.sim-console.enabled", havingValue = "true")
class SimConsoleController {

    record Phone(String phone, String name, String kind, UUID councilId, String council, List<String> stalls) {}

    record TextRequest(String from, String body) {}

    record TextAccepted(boolean stored) {}

    record Message(String direction, String body, Instant at, String status, String template) {}

    private final TenantContext tenant;
    private final CouncilRepository councils;
    private final JdbcClient jdbc;
    private final SmsInbox inbox;
    private final Clock clock;

    SimConsoleController(TenantContext tenant, CouncilRepository councils, JdbcClient jdbc, SmsInbox inbox, Clock clock) {
        this.tenant = tenant;
        this.councils = councils;
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.clock = clock;
    }

    /** Every trader and agent in every council, one council at a time. */
    @GetMapping("/api/v1/sim/phones")
    List<Phone> phones() {
        List<Phone> phones = new ArrayList<>();
        tenant.forEachCouncil(councilId -> {
            String name = councils.find(councilId).map(c -> c.name()).orElse("?");
            phones.addAll(jdbc.sql("select t.msisdn, t.display_name, "
                            + "coalesce(array_agg(s.code order by s.code) filter (where s.code is not null), '{}') as stalls "
                            + "from trader t left join stall_allocation a on a.trader_id = t.id and a.valid_to is null "
                            + "left join stall s on s.id = a.stall_id where t.council_id = :c "
                            + "group by t.msisdn, t.display_name order by t.display_name")
                    .param("c", councilId)
                    .query((rs, n) -> new Phone(rs.getString("msisdn"), rs.getString("display_name"), "TRADER", councilId,
                            name, List.of((String[]) rs.getArray("stalls").getArray())))
                    .list());
            phones.addAll(jdbc.sql("select msisdn, display_name from agent where council_id = :c and active order by display_name")
                    .param("c", councilId)
                    .query((rs, n) -> new Phone(rs.getString("msisdn"), rs.getString("display_name"), "AGENT", councilId,
                            name, List.of()))
                    .list());
        });
        return phones;
    }

    /** Texts Msika as the given phone, exactly as if the gateway had delivered it. */
    @PostMapping("/api/v1/sim/sms")
    TextAccepted text(@RequestBody TextRequest request) {
        if (request.body() == null || request.body().isEmpty() || request.body().length() > 1600) {
            throw new InvalidInputException("body must be 1 to 1600 characters");
        }
        PhoneNumber from = PhoneNumber.parse(request.from() == null ? "" : request.from());
        return new TextAccepted(inbox.receive("console-" + UUID.randomUUID(), from, request.body(), clock.instant()));
    }

    /** A phone's conversation: what it sent and what Msika sent it, oldest first. */
    @GetMapping("/api/v1/sim/sms")
    List<Message> conversation(@RequestParam("phone") String phone) {
        String e164 = PhoneNumber.parse(phone).e164();
        List<Message> messages = new ArrayList<>();
        tenant.forEachSmsTenant(council -> {
            messages.addAll(jdbc.sql("select body, stored_at, status, outcome from sms_inbound where from_msisdn = :p")
                    .param("p", e164)
                    .query((rs, n) -> new Message("IN", rs.getString("body"),
                            rs.getObject("stored_at", OffsetDateTime.class).toInstant(), rs.getString("status"),
                            rs.getString("outcome")))
                    .list());
            messages.addAll(jdbc.sql("select body, created_at, status, template from sms_outbound where to_msisdn = :p")
                    .param("p", e164)
                    .query((rs, n) -> new Message("OUT", rs.getString("body"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("status"),
                            rs.getString("template")))
                    .list());
        });
        messages.sort(Comparator.comparing(Message::at));
        return messages;
    }
}
