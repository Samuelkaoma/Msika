package zm.msika.core.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zm.msika.core.common.InvalidInputException;

/** Specification for {@link PhoneNumber}: one number however it is written, and never logged in full. */
class PhoneNumberTest {

    @Nested
    @DisplayName("normalisation to E.164")
    class Normalisation {

        @Test
        @DisplayName("0977..., 260977... and +260977... give the same number")
        void threeWaysOfWritingTheSameNumber() {
            PhoneNumber local = PhoneNumber.parse("0977000739");
            PhoneNumber withoutPlus = PhoneNumber.parse("260977000739");
            PhoneNumber international = PhoneNumber.parse("+260977000739");

            assertThat(local).isEqualTo(withoutPlus).isEqualTo(international);
            assertThat(local.e164()).isEqualTo("+260977000739");
        }

        @ParameterizedTest(name = "[{0}] becomes +260977000739")
        @ValueSource(strings = {
            "0977000739", "260977000739", "+260977000739", "00260977000739",
            "977000739", "  +260 977 000 739  ", "0977-000-739", "(097) 7000-739", "+260.977.000.739",
        })
        void everyUsualWayOfWritingIt(String written) {
            assertThat(PhoneNumber.parse(written).e164()).isEqualTo("+260977000739");
        }

        @ParameterizedTest(name = "[{0}] is a valid mobile prefix")
        @ValueSource(strings = {"0955123456", "0966123456", "0977123456", "0761234567", "0771234567"})
        void commonMobilePrefixes(String written) {
            assertThat(PhoneNumber.parse(written).e164()).startsWith("+260").hasSize(13);
        }

        @Test
        @DisplayName("the national form is how people write it locally")
        void nationalForm() {
            assertThat(PhoneNumber.parse("+260977000739").national()).isEqualTo("0977000739");
        }
    }

    @Nested
    @DisplayName("what is refused")
    class Refused {

        @ParameterizedTest(name = "[{0}] is not a Zambian mobile number")
        @ValueSource(strings = {
            "", "   ", "abc", "0977 12 AB 39", "097700073", "09770007399", "+26097700073",
            "+2609770007399", "+27821234567", "0027821234567", "+1 202 555 0100", "0211123456",
            "+260 211 123 456", "++260977000739", "0977000739+", "+260-977-000-73-9-9",
        })
        void notAMobileNumber(String written) {
            assertThatThrownBy(() -> PhoneNumber.parse(written))
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageContaining("phone number");
        }

        @Test
        @DisplayName("null is refused")
        void nullRefused() {
            assertThatThrownBy(() -> PhoneNumber.parse(null)).isInstanceOf(InvalidInputException.class);
        }

        @Test
        @DisplayName("the constructor accepts only E.164, so an unnormalised number cannot be stored")
        void constructorOnlyAcceptsE164() {
            assertThatThrownBy(() -> new PhoneNumber("0977000739")).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> new PhoneNumber("260977000739")).isInstanceOf(InvalidInputException.class);
            assertThat(new PhoneNumber("+260977000739").e164()).isEqualTo("+260977000739");
        }
    }

    @Nested
    @DisplayName("masking, so a number never reaches a log in full")
    class Masking {

        @Test
        @DisplayName("masked shows the first four and last three digits, like 0977***739")
        void maskedForm() {
            assertThat(PhoneNumber.parse("+260977000739").masked()).isEqualTo("0977***739");
            assertThat(PhoneNumber.parse("0966000001").masked()).isEqualTo("0966***001");
        }

        @Test
        @DisplayName("printing a phone number gives the masked form, never the full number")
        void toStringIsMasked() {
            PhoneNumber phone = PhoneNumber.parse("0977000739");

            assertThat(phone.toString()).isEqualTo("0977***739");
            assertThat("logged: " + phone).doesNotContain("000");
            assertThat(String.valueOf(phone)).doesNotContain("+260977000739");
        }

        @Test
        @DisplayName("masking hides the middle digits")
        void middleDigitsHidden() {
            String masked = PhoneNumber.parse("0977000739").masked();

            assertThat(masked).doesNotContain("000").doesNotContain("0007");
            assertThat(masked).hasSize(10);
        }
    }
}
