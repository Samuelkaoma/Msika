package zm.msika.core.sms;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides what to do with one received text, before any use case runs (card M-18): answer it straight away (an
 * unknown sender, a mistake, {@code HELP}, a command for the other kind of user), or hand the command on. Pure, so
 * every rule in {@code docs/SMS-PROTOCOL.md} about who may send what is tested without a database.
 */
public final class SmsRouting {

    /** One council's record of the sender: a trader, an agent, or both. */
    public record Sender(UUID councilId, UUID traderId, UUID agentId) {
        public Sender {
            Objects.requireNonNull(councilId, "councilId must not be null");
            if (traderId == null && agentId == null) {
                throw new IllegalArgumentException("a sender is a trader, an agent or both");
            }
        }

        public boolean isTrader() {
            return traderId != null;
        }

        public boolean isAgent() {
            return agentId != null;
        }
    }

    /** What to do. */
    public sealed interface Decision {
        /**
         * Answer with this template and nothing else.
         *
         * @param councilId the council the answer belongs to, or null when the sender has none
         */
        record Answer(UUID councilId, SmsTemplate template, Map<String, String> values, String outcome) implements Decision {}

        /**
         * Run the command's use case.
         *
         * @param sender the sender's record, or null for a {@code REG} from a number nobody has registered
         */
        record Handle(Sender sender, SmsCommand command) implements Decision {}
    }

    private SmsRouting() {}

    /**
     * @param matches every council's record of the sending number (usually zero or one)
     * @param parsed what the parser made of the text
     * @param maxNgwee the parser's largest amount, for the "too large" answer
     */
    public static Decision decide(List<Sender> matches, SmsCommandParser.Result parsed, long maxNgwee) {
        if (matches.size() > 1) {
            return answer(null, SmsTemplate.SENDER_AMBIGUOUS, Map.of(), "SENDER_AMBIGUOUS");
        }
        Sender sender = matches.isEmpty() ? null : matches.get(0);
        UUID council = sender == null ? null : sender.councilId();

        if (parsed instanceof SmsCommandParser.Result.Failed failed) {
            // A stranger only hears how to register, unless they were trying to register.
            if (sender == null && !"REG".equals(failed.command())) {
                return answer(null, SmsTemplate.NOT_REGISTERED, Map.of(), "NOT_REGISTERED");
            }
            return mistake(council, failed, maxNgwee);
        }
        SmsCommand command = ((SmsCommandParser.Result.Parsed) parsed).command();
        String word = word(command);

        if (sender == null) {
            return command instanceof SmsCommand.Register ? new Decision.Handle(null, command)
                    : answer(null, SmsTemplate.NOT_REGISTERED, Map.of(), "NOT_REGISTERED");
        }
        if (command instanceof SmsCommand.Help) {
            return answer(council, sender.isTrader() ? SmsTemplate.HELP_TRADER : SmsTemplate.HELP_AGENT, Map.of(), "HELP");
        }
        if (command.audience() == SmsCommand.Audience.AGENT && !sender.isAgent()) {
            return answer(council, SmsTemplate.AGENTS_ONLY, Map.of("COMMAND", word), "AGENTS_ONLY");
        }
        if (command.audience() == SmsCommand.Audience.TRADER && !sender.isTrader()) {
            return answer(council, SmsTemplate.TRADERS_ONLY, Map.of("COMMAND", word), "TRADERS_ONLY");
        }
        return new Decision.Handle(sender, command);
    }

    private static Decision answer(UUID council, SmsTemplate template, Map<String, String> values, String outcome) {
        return new Decision.Answer(council, template, values, outcome);
    }

    private static Decision mistake(UUID council, SmsCommandParser.Result.Failed failed, long maxNgwee) {
        String command = failed.command();
        String outcome = failed.error().name();
        return switch (failed.error()) {
            case EMPTY, UNKNOWN_COMMAND -> answer(council, SmsTemplate.UNKNOWN_COMMAND, Map.of(), outcome);
            case MISSING_ARGUMENT, EXTRA_WORDS -> answer(council, usage(command), usage(command) == SmsTemplate.USAGE_PLAIN
                    ? Map.of("COMMAND", command) : Map.of(), outcome);
            case INVALID_AMOUNT -> answer(council, SmsTemplate.INVALID_AMOUNT, Map.of("COMMAND", command), outcome);
            case AMOUNT_NOT_POSITIVE -> answer(council, SmsTemplate.AMOUNT_NOT_POSITIVE, Map.of(), outcome);
            case AMOUNT_TOO_LARGE -> answer(council, SmsTemplate.AMOUNT_TOO_LARGE,
                    Map.of("AMOUNT", SmsFormat.kwacha(maxNgwee)), outcome);
            case INVALID_STALL_CODE -> answer(council, SmsTemplate.INVALID_STALL, Map.of(), outcome);
            case INVALID_REFERENCE -> answer(council, SmsTemplate.INVALID_REFERENCE, Map.of(), outcome);
        };
    }

    private static SmsTemplate usage(String command) {
        return switch (command) {
            case "REG" -> SmsTemplate.USAGE_REG;
            case "SALE", "S" -> SmsTemplate.USAGE_SALE;
            case "DEP" -> SmsTemplate.USAGE_DEP;
            case "NO" -> SmsTemplate.USAGE_NO;
            case "UNDO" -> SmsTemplate.USAGE_UNDO;
            case "LOAN" -> SmsTemplate.USAGE_LOAN;
            default -> SmsTemplate.USAGE_PLAIN;
        };
    }

    /** The command word a user types for a command, for replies like "DEP is for agents only". */
    public static String word(SmsCommand command) {
        return switch (command) {
            case SmsCommand.Register r -> "REG";
            case SmsCommand.Consent c -> "YES";
            case SmsCommand.DeclareSale s -> "SALE";
            case SmsCommand.Today t -> "TODAY";
            case SmsCommand.Balance b -> "BAL";
            case SmsCommand.Levy l -> "LEVY";
            case SmsCommand.Month m -> "MONTH";
            case SmsCommand.StatementOk o -> "OK";
            case SmsCommand.StatementWrong w -> "WRONG";
            case SmsCommand.DisputeDeposit d -> "NO";
            case SmsCommand.Loan l -> "LOAN";
            case SmsCommand.Help h -> "HELP";
            case SmsCommand.Stop s -> "STOP";
            case SmsCommand.Start s -> "START";
            case SmsCommand.Deposit d -> "DEP";
            case SmsCommand.Undo u -> "UNDO";
            case SmsCommand.Round r -> "ROUND";
        };
    }
}
