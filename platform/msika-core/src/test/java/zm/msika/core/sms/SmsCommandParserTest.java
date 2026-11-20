package zm.msika.core.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import zm.msika.core.sms.SmsCommandParser.Error;
import zm.msika.core.sms.SmsCommandParser.Result;

/** Every example in {@code docs/SMS-PROTOCOL.md}, and the awkward amounts people really type (card M-17). */
class SmsCommandParserTest {

    private final SmsCommandParser parser = new SmsCommandParser();

    private SmsCommand ok(String body) {
        Result result = parser.parse(body);
        assertThat(result).as(body).isInstanceOf(Result.Parsed.class);
        return ((Result.Parsed) result).command();
    }

    private Error error(String body) {
        Result result = parser.parse(body);
        assertThat(result).as(body).isInstanceOf(Result.Failed.class);
        return ((Result.Failed) result).error();
    }

    @Nested
    @DisplayName("the examples in SMS-PROTOCOL.md")
    class ProtocolExamples {

        @Test
        void general() {
            // "dep b114 450, DEP  B114  450 and Dep B114 K450 are the same message"
            SmsCommand expected = new SmsCommand.Deposit("B114", 45_000);
            assertThat(ok("dep b114 450")).isEqualTo(expected);
            assertThat(ok("DEP  B114  450")).isEqualTo(expected);
            assertThat(ok("Dep B114 K450")).isEqualTo(expected);
            // "Commas in amounts are ignored (1,200)"
            assertThat(ok("SALE 1,200")).isEqualTo(new SmsCommand.DeclareSale(120_000));
            // "450, 450.5, K12.50"
            assertThat(ok("SALE 450")).isEqualTo(new SmsCommand.DeclareSale(45_000));
            assertThat(ok("SALE 450.5")).isEqualTo(new SmsCommand.DeclareSale(45_050));
            assertThat(ok("SALE K12.50")).isEqualTo(new SmsCommand.DeclareSale(1_250));
        }

        @Test
        void traderCommands() {
            assertThat(ok("REG SOW-B-114")).isEqualTo(new SmsCommand.Register("SOW-B-114"));
            assertThat(ok("YES")).isEqualTo(new SmsCommand.Consent());
            assertThat(ok("SALE 120")).isEqualTo(new SmsCommand.DeclareSale(12_000));
            assertThat(ok("S 120")).isEqualTo(new SmsCommand.DeclareSale(12_000));
            assertThat(ok("TODAY")).isEqualTo(new SmsCommand.Today());
            assertThat(ok("BAL")).isEqualTo(new SmsCommand.Balance());
            assertThat(ok("LEVY")).isEqualTo(new SmsCommand.Levy());
            assertThat(ok("MONTH")).isEqualTo(new SmsCommand.Month());
            assertThat(ok("OK")).isEqualTo(new SmsCommand.StatementOk());
            assertThat(ok("WRONG")).isEqualTo(new SmsCommand.StatementWrong());
            assertThat(ok("NO 8F2K")).isEqualTo(new SmsCommand.DisputeDeposit("8F2K"));
            assertThat(ok("LOAN")).isEqualTo(new SmsCommand.Loan(null, null));
            assertThat(ok("LOAN 800 SUP-12")).isEqualTo(new SmsCommand.Loan(80_000L, "SUP-12"));
            assertThat(ok("HELP")).isEqualTo(new SmsCommand.Help());
            assertThat(ok("STOP")).isEqualTo(new SmsCommand.Stop());
            assertThat(ok("START")).isEqualTo(new SmsCommand.Start());
        }

        @Test
        void agentCommands() {
            assertThat(ok("DEP B114 450")).isEqualTo(new SmsCommand.Deposit("B114", 45_000));
            assertThat(ok("UNDO 8F2K")).isEqualTo(new SmsCommand.Undo("8F2K"));
            assertThat(ok("ROUND")).isEqualTo(new SmsCommand.Round());
        }

        @Test
        void audiences() {
            assertThat(ok("DEP B114 450").audience()).isEqualTo(SmsCommand.Audience.AGENT);
            assertThat(ok("UNDO 8F2K").audience()).isEqualTo(SmsCommand.Audience.AGENT);
            assertThat(ok("ROUND").audience()).isEqualTo(SmsCommand.Audience.AGENT);
            assertThat(ok("SALE 120").audience()).isEqualTo(SmsCommand.Audience.TRADER);
            assertThat(ok("NO 8F2K").audience()).isEqualTo(SmsCommand.Audience.TRADER);
            assertThat(ok("HELP").audience()).isEqualTo(SmsCommand.Audience.ANYONE);
        }
    }

    @Nested
    @DisplayName("amounts")
    class Amounts {

        @ParameterizedTest(name = "{0} is {1} ngwee")
        @CsvSource(delimiter = '|', value = {
            "1,200      | 120000",
            "K1,200     | 120000",
            "k1,200.50  | 120050",
            "12,345.6   | 1234560",
            "0.01       | 1",
            "007        | 700",
            "K50,000    | 5000000",
            "50000.00   | 5000000",
        })
        void accepted(String amount, long ngwee) {
            assertThat(ok("SALE " + amount)).isEqualTo(new SmsCommand.DeclareSale(ngwee));
        }

        @ParameterizedTest(name = "{0} is not positive")
        @ValueSource(strings = {"0", "K0", "0.00", "-450", "K-450", "-0.5", "-99999999999999999999"})
        void zeroAndNegative(String amount) {
            assertThat(error("SALE " + amount)).isEqualTo(Error.AMOUNT_NOT_POSITIVE);
        }

        @ParameterizedTest(name = "{0} is too large")
        @ValueSource(strings = {"50000.01", "K50,001", "1000000", "99999999999999999999999"})
        void tooLarge(String amount) {
            assertThat(error("SALE " + amount)).isEqualTo(Error.AMOUNT_TOO_LARGE);
        }

        @ParameterizedTest(name = "{0} is not an amount")
        @ValueSource(strings = {"abc", "4.505", ".5", "K", ",", "4x0", "ZMW450", "1e3", "--5", "4,5.0.0"})
        void notAnAmount(String amount) {
            assertThat(error("SALE " + amount)).isEqualTo(Error.INVALID_AMOUNT);
        }

        @Test
        @DisplayName("the largest amount is configurable")
        void configurableMaximum() {
            SmsCommandParser small = new SmsCommandParser(10_000);
            assertThat(small.parse("SALE 100")).isEqualTo(new Result.Parsed(new SmsCommand.DeclareSale(10_000)));
            assertThat(small.parse("SALE 100.01")).isEqualTo(new Result.Failed(Error.AMOUNT_TOO_LARGE, "SALE"));
            assertThatThrownBy(() -> new SmsCommandParser(0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("deposit amounts follow the same rules")
        void depositAmounts() {
            assertThat(ok("DEP B114 1,200.5")).isEqualTo(new SmsCommand.Deposit("B114", 120_050));
            assertThat(error("DEP B114 0")).isEqualTo(Error.AMOUNT_NOT_POSITIVE);
            assertThat(error("DEP B114 -5")).isEqualTo(Error.AMOUNT_NOT_POSITIVE);
            assertThat(error("DEP B114 60000")).isEqualTo(Error.AMOUNT_TOO_LARGE);
            assertThat(error("DEP B114 lots")).isEqualTo(Error.INVALID_AMOUNT);
        }
    }

    @Nested
    @DisplayName("mistakes")
    class Mistakes {

        @Test
        void emptyAndUnknown() {
            assertThat(error("")).isEqualTo(Error.EMPTY);
            assertThat(error("   ")).isEqualTo(Error.EMPTY);
            assertThat(error("HELLO")).isEqualTo(Error.UNKNOWN_COMMAND);
            assertThat(error("450 DEP B114")).isEqualTo(Error.UNKNOWN_COMMAND);
            assertThat(parser.parse("HELLO")).isEqualTo(new Result.Failed(Error.UNKNOWN_COMMAND, null));
        }

        @Test
        void missingArguments() {
            assertThat(parser.parse("DEP")).isEqualTo(new Result.Failed(Error.MISSING_ARGUMENT, "DEP"));
            assertThat(error("DEP B114")).isEqualTo(Error.MISSING_ARGUMENT);
            assertThat(error("SALE")).isEqualTo(Error.MISSING_ARGUMENT);
            assertThat(error("NO")).isEqualTo(Error.MISSING_ARGUMENT);
            assertThat(error("UNDO")).isEqualTo(Error.MISSING_ARGUMENT);
            assertThat(error("REG")).isEqualTo(Error.MISSING_ARGUMENT);
            assertThat(error("LOAN 800")).isEqualTo(Error.MISSING_ARGUMENT);
        }

        @Test
        @DisplayName("extra words are refused rather than guessed at")
        void extraWords() {
            assertThat(error("DEP B114 450 500")).isEqualTo(Error.EXTRA_WORDS);
            assertThat(error("SALE 120 tomatoes")).isEqualTo(Error.EXTRA_WORDS);
            assertThat(error("TODAY please")).isEqualTo(Error.EXTRA_WORDS);
            assertThat(error("NO 8F2K 9G3M")).isEqualTo(Error.EXTRA_WORDS);
            assertThat(error("REG SOW-B-114 SOW-B-115")).isEqualTo(Error.EXTRA_WORDS);
            assertThat(error("LOAN 800 SUP-12 now")).isEqualTo(Error.EXTRA_WORDS);
        }

        @Test
        void references() {
            assertThat(ok("no 8f2k")).isEqualTo(new SmsCommand.DisputeDeposit("8F2K"));
            assertThat(error("NO 8F2")).isEqualTo(Error.INVALID_REFERENCE);
            assertThat(error("NO 8F2KK")).isEqualTo(Error.INVALID_REFERENCE);
            // 0, O, 1, I and L are left out of the alphabet so they cannot be confused.
            assertThat(error("NO 0F2K")).isEqualTo(Error.INVALID_REFERENCE);
            assertThat(error("NO OF2K")).isEqualTo(Error.INVALID_REFERENCE);
            assertThat(error("UNDO 1L2K")).isEqualTo(Error.INVALID_REFERENCE);
        }

        @Test
        void stallCodes() {
            assertThat(ok("reg sow-b-114")).isEqualTo(new SmsCommand.Register("SOW-B-114"));
            assertThat(error("REG SOW--B")).isEqualTo(Error.INVALID_STALL_CODE);
            assertThat(error("REG -B114")).isEqualTo(Error.INVALID_STALL_CODE);
            assertThat(error("DEP B/114 450")).isEqualTo(Error.INVALID_STALL_CODE);
            assertThat(error("REG " + "A".repeat(31))).isEqualTo(Error.INVALID_STALL_CODE);
        }

        @Test
        @DisplayName("a closing full stop, surrounding spaces and no-break spaces are tolerated")
        void punctuationAndSpaces() {
            assertThat(ok("  DEP B114 450.  ")).isEqualTo(new SmsCommand.Deposit("B114", 45_000));
            assertThat(ok("Today!")).isEqualTo(new SmsCommand.Today());
            assertThat(ok("DEP B114 450")).isEqualTo(new SmsCommand.Deposit("B114", 45_000));
            assertThat(ok("DEP\tB114\n450")).isEqualTo(new SmsCommand.Deposit("B114", 45_000));
        }
    }
}
