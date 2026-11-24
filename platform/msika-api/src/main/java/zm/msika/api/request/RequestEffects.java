package zm.msika.api.request;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import zm.msika.api.sms.SmsStore;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.request.RequestKind;
import zm.msika.core.sms.SmsTemplate;

/**
 * What each kind of request does when it is approved, and how that is said beforehand. Every method runs in the
 * caller's transaction, so an effect that throws takes the decision back with it (COUNCIL-ADMIN.md rule 4).
 */
@Component
class RequestEffects {

    private static final Pattern CLASS = Pattern.compile("[A-Z0-9_]{1,20}");

    private final JdbcClient jdbc;
    private final SmsStore sms;
    private final Clock clock;

    RequestEffects(JdbcClient jdbc, SmsStore sms, Clock clock) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.clock = clock;
    }

    /** @param marketId the market the subject is in, or null */
    record Subject(UUID marketId, String words) {}

    private record StallRow(UUID marketId, String code, String status, String stallClass) {}

    private StallRow stall(UUID council, UUID id) {
        return jdbc.sql("select market_id, code, status, stall_class from stall where council_id = :c and id = :id for update")
                .param("c", council).param("id", id)
                .query((rs, n) -> new StallRow(rs.getObject("market_id", UUID.class), rs.getString("code"), rs.getString("status"),
                        rs.getString("stall_class")))
                .optional().orElseThrow(() -> new NotFoundException("stall not found"));
    }

    /** Checks the request makes sense and says in words what approving it will do. */
    Subject describe(UUID council, RequestKind kind, UUID subject, Map<String, String> effect) {
        if (kind == RequestKind.AGENT_SUSPENSION) {
            boolean active = agentActive(effect);
            String name = jdbc.sql("select display_name from agent where council_id = :c and id = :id").param("c", council)
                    .param("id", subject).query(String.class).optional().orElseThrow(() -> new NotFoundException("agent not found"));
            return new Subject(null, active ? "Let agent " + name + " use agent commands again"
                    : "Stop agent " + name + " from using agent commands");
        }
        StallRow s = stall(council, subject);
        return new Subject(s.marketId(), switch (kind) {
            case STALL_CLASS -> "Change stall " + s.code() + " from class " + s.stallClass() + " to " + stallClass(effect);
            case STALL_SUSPEND -> "Suspend stall " + s.code() + ": no deposits or allocations until it is reinstated";
            case STALL_REINSTATE -> "Put stall " + s.code() + " back in use";
            case AGENT_SUSPENSION -> throw new IllegalStateException("handled above");
        });
    }

    /** @throws ConflictException if it cannot be done now, with the reason to show */
    void run(UUID council, RequestKind kind, UUID subject, Map<String, String> effect) {
        switch (kind) {
            case AGENT_SUSPENSION -> {
                boolean active = agentActive(effect);
                String phone = jdbc.sql("update agent set active = :a where council_id = :c and id = :id and active <> :a "
                                + "returning msisdn").param("a", active).param("c", council).param("id", subject)
                        .query(String.class).optional()
                        .orElseThrow(() -> new ConflictException(active ? "the agent is already active" : "the agent is already suspended"));
                SmsTemplate t = active ? SmsTemplate.AGENT_REINSTATED : SmsTemplate.AGENT_SUSPENDED;
                sms.enqueue(council, phone, t.render(), t.name(), null, clock.instant());
            }
            case STALL_CLASS -> {
                StallRow s = stall(council, subject);
                String to = stallClass(effect);
                if (s.stallClass().equals(to)) {
                    throw new ConflictException("stall " + s.code() + " already has class " + to);
                }
                jdbc.sql("update stall set stall_class = :v where council_id = :c and id = :id").param("v", to)
                        .param("c", council).param("id", subject).update();
                tell(council, subject, SmsTemplate.STALL_CLASS_CHANGED, "STALL", s.code(), "CLASS", to);
            }
            case STALL_SUSPEND -> {
                StallRow s = stall(council, subject);
                if (s.status().equals("SUSPENDED")) {
                    throw new ConflictException("stall " + s.code() + " is already suspended");
                }
                status(council, subject, "SUSPENDED");
                tell(council, subject, SmsTemplate.STALL_SUSPENDED, "STALL", s.code());
            }
            case STALL_REINSTATE -> {
                StallRow s = stall(council, subject);
                if (!s.status().equals("SUSPENDED")) {
                    throw new ConflictException("stall " + s.code() + " is not suspended");
                }
                boolean held = jdbc.sql("select count(*) from stall_allocation where council_id = :c and stall_id = :id "
                        + "and valid_to is null").param("c", council).param("id", subject).query(Integer.class).single() > 0;
                status(council, subject, held ? "ALLOCATED" : "VACANT");
                tell(council, subject, SmsTemplate.STALL_REINSTATED, "STALL", s.code());
            }
        }
    }

    private void status(UUID council, UUID stall, String status) {
        jdbc.sql("update stall set status = :s where council_id = :c and id = :id").param("s", status).param("c", council)
                .param("id", stall).update();
    }

    /** Tells the trader who holds the stall, if anyone does. */
    private void tell(UUID council, UUID stall, SmsTemplate template, String... values) {
        jdbc.sql("select t.msisdn from stall_allocation a join trader t on t.id = a.trader_id where a.council_id = :c "
                        + "and a.stall_id = :id and a.valid_to is null").param("c", council).param("id", stall)
                .query(String.class).optional()
                .ifPresent(phone -> sms.enqueue(council, phone, template.render(values), template.name(), null, clock.instant()));
    }

    private static boolean agentActive(Map<String, String> effect) {
        return switch (effect.getOrDefault("active", "")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidInputException("active must be \"true\" or \"false\"");
        };
    }

    private static String stallClass(Map<String, String> effect) {
        String c = effect.getOrDefault("stallClass", "");
        if (!CLASS.matcher(c).matches()) {
            throw new InvalidInputException("stallClass must be 1 to 20 capitals, digits or underscores");
        }
        return c;
    }
}
