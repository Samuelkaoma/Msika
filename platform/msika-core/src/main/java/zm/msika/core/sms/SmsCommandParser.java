package zm.msika.core.sms;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the body of a text message into an {@link SmsCommand}, or says exactly why it cannot (card M-17). It is
 * pure: no database, no Spring, no clock. Whether the stall exists or the sender may use the command is decided
 * later, by the use case the command goes to.
 *
 * <p>The rules are those of {@code docs/SMS-PROTOCOL.md}:
 *
 * <ul>
 *   <li>case and spacing do not matter;
 *   <li>amounts are kwacha with at most two decimals, an optional leading {@code K}, and commas ignored;
 *   <li>an amount must be more than zero and at most the configured maximum (K50,000 by default);
 *   <li>references are four characters from {@code 23456789ABCDEFGHJKMNPQRSTUVWXYZ}.
 * </ul>
 *
 * Extra words after a complete command are refused rather than ignored: {@code DEP B114 450 500} is far more
 * likely a typing mistake than a deposit of K450, and money must not be recorded on a guess.
 */
public final class SmsCommandParser {

    /** The default largest amount one message may carry: K50,000. */
    public static final long DEFAULT_MAX_NGWEE = 5_000_000L;

    /** Why a message could not be parsed. Each has its own reply, so the sender knows what to fix. */
    public enum Error {
        /** Nothing but spaces. */
        EMPTY,
        /** The first word is not a command. */
        UNKNOWN_COMMAND,
        /** The command needs a stall code, amount or reference that is not there. */
        MISSING_ARGUMENT,
        /** More words than the command takes. */
        EXTRA_WORDS,
        /** The amount is not a number of kwacha with at most two decimals. */
        INVALID_AMOUNT,
        /** The amount is zero or negative. */
        AMOUNT_NOT_POSITIVE,
        /** The amount is more than one message may carry. */
        AMOUNT_TOO_LARGE,
        /** The stall code has characters a stall code never has. */
        INVALID_STALL_CODE,
        /** The reference is not four characters from the reference alphabet. */
        INVALID_REFERENCE
    }

    /** The outcome: a command, or an error with the word that caused it. */
    public sealed interface Result {
        /** Parsed successfully. */
        record Parsed(SmsCommand command) implements Result {}

        /**
         * Could not be parsed.
         *
         * @param command the command word, upper case, when it was recognised (so the reply can show the right
         *     usage); null otherwise
         */
        record Failed(Error error, String command) implements Result {}
    }

    private static final Pattern AMOUNT = Pattern.compile("K?(-?)(\\d+)(?:\\.(\\d{1,2}))?");
    private static final Pattern STALL_CODE = Pattern.compile("[A-Z0-9]+(?:-[A-Z0-9]+)*");
    private static final Pattern REFERENCE = Pattern.compile("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}");
    private static final int MAX_STALL_CODE = 30;

    private final long maxNgwee;

    public SmsCommandParser() {
        this(DEFAULT_MAX_NGWEE);
    }

    /** @param maxNgwee the largest amount one message may carry */
    public SmsCommandParser(long maxNgwee) {
        if (maxNgwee <= 0) {
            throw new IllegalArgumentException("maxNgwee must be positive");
        }
        this.maxNgwee = maxNgwee;
    }

    public Result parse(String body) {
        Objects.requireNonNull(body, "body must not be null");
        // Any kind of space (including the no-break space some phones insert) separates words; a full stop or
        // exclamation mark at the very end is how people finish a sentence, not part of the command.
        String cleaned = body.replace(' ', ' ').strip().replaceAll("[.!]+$", "").strip();
        if (cleaned.isEmpty()) {
            return new Result.Failed(Error.EMPTY, null);
        }
        List<String> words = Arrays.asList(cleaned.toUpperCase(Locale.ROOT).split("\\s+"));
        String command = words.get(0);
        List<String> args = words.subList(1, words.size());
        return switch (command) {
            case "REG" -> stallOnly(command, args);
            case "YES" -> none(command, args, new SmsCommand.Consent());
            case "SALE", "S" -> sale(command, args);
            case "TODAY" -> none(command, args, new SmsCommand.Today());
            case "BAL" -> none(command, args, new SmsCommand.Balance());
            case "LEVY" -> none(command, args, new SmsCommand.Levy());
            case "MONTH" -> none(command, args, new SmsCommand.Month());
            case "OK" -> none(command, args, new SmsCommand.StatementOk());
            case "WRONG" -> none(command, args, new SmsCommand.StatementWrong());
            case "NO" -> reference(command, args, SmsCommand.DisputeDeposit::new);
            case "LOAN" -> loan(command, args);
            case "HELP" -> none(command, args, new SmsCommand.Help());
            case "STOP" -> none(command, args, new SmsCommand.Stop());
            case "START" -> none(command, args, new SmsCommand.Start());
            case "DEP" -> deposit(command, args);
            case "UNDO" -> reference(command, args, SmsCommand.Undo::new);
            case "ROUND" -> none(command, args, new SmsCommand.Round());
            default -> new Result.Failed(Error.UNKNOWN_COMMAND, null);
        };
    }

    private static Result none(String command, List<String> args, SmsCommand parsed) {
        return args.isEmpty() ? new Result.Parsed(parsed) : new Result.Failed(Error.EXTRA_WORDS, command);
    }

    private static Result stallOnly(String command, List<String> args) {
        if (args.isEmpty()) {
            return new Result.Failed(Error.MISSING_ARGUMENT, command);
        }
        if (args.size() > 1) {
            return new Result.Failed(Error.EXTRA_WORDS, command);
        }
        if (!isStallCode(args.get(0))) {
            return new Result.Failed(Error.INVALID_STALL_CODE, command);
        }
        return new Result.Parsed(new SmsCommand.Register(args.get(0)));
    }

    private Result sale(String command, List<String> args) {
        if (args.isEmpty()) {
            return new Result.Failed(Error.MISSING_ARGUMENT, command);
        }
        if (args.size() > 1) {
            return new Result.Failed(Error.EXTRA_WORDS, command);
        }
        Amount amount = amount(args.get(0));
        return amount.error != null ? new Result.Failed(amount.error, command)
                : new Result.Parsed(new SmsCommand.DeclareSale(amount.ngwee));
    }

    private Result deposit(String command, List<String> args) {
        if (args.size() < 2) {
            return new Result.Failed(Error.MISSING_ARGUMENT, command);
        }
        if (args.size() > 2) {
            return new Result.Failed(Error.EXTRA_WORDS, command);
        }
        if (!isStallCode(args.get(0))) {
            return new Result.Failed(Error.INVALID_STALL_CODE, command);
        }
        Amount amount = amount(args.get(1));
        return amount.error != null ? new Result.Failed(amount.error, command)
                : new Result.Parsed(new SmsCommand.Deposit(args.get(0), amount.ngwee));
    }

    private Result loan(String command, List<String> args) {
        if (args.isEmpty()) {
            return new Result.Parsed(new SmsCommand.Loan(null, null));
        }
        if (args.size() == 1) {
            return new Result.Failed(Error.MISSING_ARGUMENT, command);
        }
        if (args.size() > 2) {
            return new Result.Failed(Error.EXTRA_WORDS, command);
        }
        Amount amount = amount(args.get(0));
        if (amount.error != null) {
            return new Result.Failed(amount.error, command);
        }
        if (!isStallCode(args.get(1))) {
            return new Result.Failed(Error.INVALID_STALL_CODE, command);
        }
        return new Result.Parsed(new SmsCommand.Loan(amount.ngwee, args.get(1)));
    }

    private static Result reference(String command, List<String> args, java.util.function.Function<String, SmsCommand> make) {
        if (args.isEmpty()) {
            return new Result.Failed(Error.MISSING_ARGUMENT, command);
        }
        if (args.size() > 1) {
            return new Result.Failed(Error.EXTRA_WORDS, command);
        }
        String ref = args.get(0);
        return REFERENCE.matcher(ref).matches() ? new Result.Parsed(make.apply(ref))
                : new Result.Failed(Error.INVALID_REFERENCE, command);
    }

    private static boolean isStallCode(String word) {
        return word.length() <= MAX_STALL_CODE && STALL_CODE.matcher(word).matches();
    }

    private record Amount(long ngwee, Error error) {}

    /** Reads an amount in kwacha into ngwee, using only integer arithmetic (AGENTS.md rule 1). */
    private Amount amount(String word) {
        String digits = word.replace(",", "");
        Matcher m = AMOUNT.matcher(digits);
        if (digits.isEmpty() || !m.matches()) {
            return new Amount(0, Error.INVALID_AMOUNT);
        }
        boolean negative = !m.group(1).isEmpty();
        String whole = m.group(2).replaceFirst("^0+(?=\\d)", "");
        String fraction = m.group(3) == null ? "00" : (m.group(3) + "0").substring(0, 2);
        // Anything with more than 15 digits of kwacha is far beyond any limit, and would overflow a long.
        if (whole.length() > 15) {
            return new Amount(0, negative ? Error.AMOUNT_NOT_POSITIVE : Error.AMOUNT_TOO_LARGE);
        }
        long ngwee = Long.parseLong(whole) * 100 + Long.parseLong(fraction);
        if (negative || ngwee == 0) {
            return new Amount(0, Error.AMOUNT_NOT_POSITIVE);
        }
        if (ngwee > maxNgwee) {
            return new Amount(0, Error.AMOUNT_TOO_LARGE);
        }
        return new Amount(ngwee, null);
    }
}
