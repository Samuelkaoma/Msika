package zm.msika.core.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every reply fits one plain SMS with the longest values it can ever hold (AGENTS.md rule 10, card M-18). */
class SmsTemplateTest {

    /** The longest value each slot can hold, made of characters that each cost one septet. */
    private static String longest(SmsTemplate.Slot slot) {
        return switch (slot) {
            case AMOUNT -> "-K9,999,999.99" + "9";
            case STALL -> "SOWET-ABCD-12345";
            case REF -> "WWWW";
            case DATE, FROMDATE -> "30 Sep";
            case MONTH -> "Sep";
            case MONTHNAME -> "September";
            case COUNT -> "99999";
            case COMMAND -> "SURRENDER";
            case NAME, REASON -> "W".repeat(slot.max() + 50);
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SmsTemplate.class)
    @DisplayName("every template fits 160 GSM-7 characters with the longest values")
    void fitsWithLongestValues(SmsTemplate template) {
        Map<String, String> values = new HashMap<>();
        for (String placeholder : template.placeholders()) {
            values.put(placeholder, longest(SmsTemplate.slotOf(placeholder)));
        }

        String reply = template.render(values);

        assertThat(Gsm7.isEncodable(reply)).as(reply).isTrue();
        assertThat(Gsm7.septets(reply)).as(reply).isLessThanOrEqualTo(160);
        assertThat(reply).startsWith("Msika");
    }

    @Test
    @DisplayName("each slot's longest test value really is as long as the slot allows")
    void longestValuesAreLongest() {
        for (SmsTemplate.Slot slot : SmsTemplate.Slot.values()) {
            assertThat(longest(slot).length()).as(slot.name()).isGreaterThanOrEqualTo(slot.max());
        }
    }

    @Test
    @DisplayName("the replies in SMS-PROTOCOL.md come out word for word")
    void protocolExamples() {
        assertThat(SmsTemplate.REG_SENT.render("STALL", "SOW-B-114"))
                .isEqualTo("Msika: request sent for stall SOW-B-114. The market office will confirm.");
        assertThat(SmsTemplate.SALE_NOTED.render("AMOUNT", "K120", "AMOUNT2", "K340", "REF", "7KQ2"))
                .isEqualTo("Msika: sale K120 noted. Today declared K340. Ref 7KQ2.");
        assertThat(SmsTemplate.TODAY.render("STALL", "SOW-B-114", "AMOUNT", "K450", "AMOUNT2", "K200",
                "AMOUNT3", "K340", "AMOUNT4", "K5"))
                .isEqualTo("Msika SOW-B-114 today: deposited K450, digital K200, declared K340, levy paid K5.");
        assertThat(SmsTemplate.BALANCE_NO_ARREARS.render("AMOUNT", "K1,230.00", "DATE", "20 Nov"))
                .isEqualTo("Msika: wallet K1,230.00. Levy paid to 20 Nov. No arrears.");
        assertThat(SmsTemplate.DEPOSIT_SENT.render("AMOUNT", "K450", "STALL", "SOW-B-114", "REF", "8F2K"))
                .isEqualTo("Msika: deposit K450 for SOW-B-114 sent. Ref 8F2K.");
        assertThat(SmsTemplate.DEPOSIT_FAILED.render("REF", "8F2K", "REASON", "not enough float"))
                .isEqualTo("Msika: deposit 8F2K FAILED: not enough float. Cash not recorded.");
        assertThat(SmsTemplate.DISPUTE_FLAGGED.render("REF", "8F2K"))
                .isEqualTo("Msika: deposit 8F2K is flagged. The market office will contact you.");
        assertThat(SmsTemplate.NOT_REGISTERED.render()).isEqualTo("Msika: this number is not registered. Ask your market "
                + "office or agent to register you, or send REG and your stall number.");
        assertThat(SmsTemplate.UNKNOWN_COMMAND.render())
                .isEqualTo("Msika: sorry, we did not understand. Send HELP for the list of commands.");
    }

    @Test
    @DisplayName("a value too long for its slot is refused; free text is cut and cleaned instead")
    void slotLimits() {
        assertThatThrownBy(() -> SmsTemplate.DEPOSIT_SENT.render("AMOUNT", "K450", "STALL", "MUCH-TOO-LONG-STALL", "REF", "8F2K"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SmsTemplate.DEPOSIT_SENT.render("AMOUNT", "K450", "STALL", "SOW-B-114"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REF");
        assertThatThrownBy(() -> SmsTemplate.DEPOSIT_SENT.render("AMOUNT", "K450", "STALL", "SOW-B-114", "REF", "8F2€"))
                .isInstanceOf(IllegalArgumentException.class);

        String reply = SmsTemplate.ROUND.render("NAME", "Mwila “Big” Banda {the agent} 😀", "COUNT", "3",
                "AMOUNT", "K1,450", "AMOUNT2", "K2,300");
        assertThat(Gsm7.isEncodable(reply)).isTrue();
        assertThat(reply).isEqualTo("Msika agent Mwila ?Big? Banda  t today: 3 deposits, K1,450. Float K2,300.");
    }

    @Test
    @DisplayName("GSM-7: curly quotes and emoji are not encodable; extension characters cost two")
    void gsm7() {
        assertThat(Gsm7.isEncodable("Plain text, K450.")).isTrue();
        assertThat(Gsm7.isEncodable("“quoted”")).isFalse();
        assertThat(Gsm7.isEncodable("😀")).isFalse();
        assertThat(Gsm7.septets("[x]")).isEqualTo(5);
        assertThat(Gsm7.fitsOneSms("W".repeat(160))).isTrue();
        assertThat(Gsm7.fitsOneSms("W".repeat(161))).isFalse();
        assertThat(Gsm7.fitsOneSms("W".repeat(159) + "€")).isFalse();
    }

    @Test
    @DisplayName("amounts and dates are written the same way everywhere")
    void formats() {
        assertThat(SmsFormat.kwacha(45_000)).isEqualTo("K450");
        assertThat(SmsFormat.kwacha(1_250)).isEqualTo("K12.50");
        assertThat(SmsFormat.kwacha(123_000, true)).isEqualTo("K1,230.00");
        assertThat(SmsFormat.kwacha(500_000_000)).isEqualTo("K5,000,000");
        assertThat(SmsFormat.kwacha(-500)).isEqualTo("-K5");
        assertThat(SmsFormat.kwacha(1)).isEqualTo("K0.01");
        assertThat(SmsFormat.kwacha(9_999_999_999L, true)).hasSizeLessThanOrEqualTo(SmsTemplate.Slot.AMOUNT.max());
        assertThat(SmsFormat.day(LocalDate.of(2026, 11, 20))).isEqualTo("20 Nov");
        assertThat(SmsFormat.month(LocalDate.of(2026, 10, 1))).isEqualTo("Oct");
        assertThat(SmsFormat.monthName(LocalDate.of(2026, 9, 1))).isEqualTo("September");
    }
}
