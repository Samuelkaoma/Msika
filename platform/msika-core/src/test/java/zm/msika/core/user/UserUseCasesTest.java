package zm.msika.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.common.ConflictException;
import zm.msika.core.common.InvalidInputException;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.Council;
import zm.msika.core.council.CreateCouncil;

/** Specification for the user rules: sign-in, creating users, deactivation, passwords. */
class UserUseCasesTest {

    private static final String GOOD_PASSWORD = "correct horse battery";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);
    private final Fakes.Councils councils = new Fakes.Councils();
    private final UserFakes.Users users = new UserFakes.Users();
    private final UserFakes.Hasher hasher = new UserFakes.Hasher();
    private final CreateUser createUser = new CreateUser(councils, users, hasher, clock);
    private final SetUserActive setActive = new SetUserActive(users);

    private Council council;

    @BeforeEach
    void setUp() {
        council = new CreateCouncil(councils, clock).execute("Lusaka City Council", null);
    }

    private CouncilUser admin() {
        return createUser.execute(council.id(), "Admin@Example.Test", GOOD_PASSWORD, RoleGrant.councilWide(Role.COUNCIL_ADMIN), "test");
    }

    @Nested
    @DisplayName("signing in")
    class SigningIn {

        private final Authenticate authenticate = new Authenticate(users, hasher);

        @Test
        @DisplayName("the right email and password sign in, whatever the case of the email")
        void signsIn() {
            CouncilUser created = admin();

            assertThat(authenticate.execute("ADMIN@example.test", GOOD_PASSWORD).id()).isEqualTo(created.id());
        }

        @Test
        @DisplayName("a wrong password is refused")
        void wrongPassword() {
            admin();

            assertThatThrownBy(() -> authenticate.execute("admin@example.test", "wrong password!!"))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("an unknown email and a wrong password give exactly the same answer")
        void sameAnswerForUnknownEmail() {
            admin();

            Throwable unknown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authenticate.execute("nobody@example.test", GOOD_PASSWORD));
            Throwable wrong = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authenticate.execute("admin@example.test", "wrong password!!"));

            assertThat(unknown).isInstanceOf(AuthenticationFailedException.class);
            assertThat(unknown).hasMessage(wrong.getMessage());
        }

        @Test
        @DisplayName("an unknown email still costs one password check, so timing does not give it away")
        void unknownEmailStillSpendsTime() {
            admin();
            int before = hasher.matchChecks;

            assertThatThrownBy(() -> authenticate.execute("nobody@example.test", GOOD_PASSWORD))
                    .isInstanceOf(AuthenticationFailedException.class);

            assertThat(hasher.matchChecks - before).isEqualTo(1);
        }

        @Test
        @DisplayName("a deactivated user cannot sign in, even with the right password")
        void deactivatedUser() {
            CouncilUser created = admin();
            setActive.execute(council.id(), created.id(), false);

            assertThatThrownBy(() -> authenticate.execute("admin@example.test", GOOD_PASSWORD))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("missing email or password is refused the same way")
        void missingFields() {
            assertThatThrownBy(() -> authenticate.execute(null, GOOD_PASSWORD))
                    .isInstanceOf(AuthenticationFailedException.class);
            assertThatThrownBy(() -> authenticate.execute("admin@example.test", null))
                    .isInstanceOf(AuthenticationFailedException.class);
        }
    }

    @Nested
    @DisplayName("creating users")
    class Creating {

        @Test
        @DisplayName("the email is stored in lower case and the password only as a hash")
        void storesHashNotPassword() {
            CouncilUser created = admin();

            assertThat(created.email()).isEqualTo("admin@example.test");
            assertThat(created.passwordHash()).isNotEqualTo(GOOD_PASSWORD);
            assertThat(created.toString()).doesNotContain("hashed").doesNotContain(GOOD_PASSWORD);
            assertThat(created.active()).isTrue();
        }

        @Test
        @DisplayName("an operator cannot be created through the API")
        void noOperators() {
            assertThatThrownBy(() -> createUser.execute(council.id(), "op@example.test", GOOD_PASSWORD, RoleGrant.councilWide(Role.MSIKA_OPERATOR), "test"))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> createUser.execute(council.id(), "op@example.test", GOOD_PASSWORD, null, "test"))
                    .isInstanceOf(InvalidInputException.class);
        }

        @Test
        @DisplayName("the same email twice, in any case, is a conflict")
        void duplicateEmail() {
            admin();

            assertThatThrownBy(() -> createUser.execute(council.id(), "ADMIN@EXAMPLE.TEST", GOOD_PASSWORD, RoleGrant.councilWide(Role.COUNCIL_OFFICER), "test"))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("a user in a council that does not exist is not found")
        void unknownCouncil() {
            assertThatThrownBy(() -> createUser.execute(UUID.randomUUID(), "a@example.test", GOOD_PASSWORD, RoleGrant.councilWide(Role.COUNCIL_ADMIN), "test"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a bad email is refused")
        void badEmail() {
            for (String bad : new String[] {"", "not-an-email", "a@b", "a b@example.test", "@example.test"}) {
                assertThatThrownBy(() -> createUser.execute(council.id(), bad, GOOD_PASSWORD, RoleGrant.councilWide(Role.COUNCIL_ADMIN), "test"))
                        .as(bad).isInstanceOf(InvalidInputException.class);
            }
        }
    }

    @Nested
    @DisplayName("passwords")
    class Passwords {

        @Test
        @DisplayName("shorter than 12 characters, blank or missing is refused")
        void tooShort() {
            for (String bad : new String[] {null, "", "   ", "short", "elevenchars"}) {
                assertThatThrownBy(() -> PasswordPolicy.check(bad)).as("[" + bad + "]").isInstanceOf(InvalidInputException.class);
            }
        }

        @Test
        @DisplayName("12 characters is enough, and 72 bytes is the limit")
        void boundaries() {
            PasswordPolicy.check("twelve chars");
            PasswordPolicy.check("x".repeat(72));
            assertThatThrownBy(() -> PasswordPolicy.check("x".repeat(73))).isInstanceOf(InvalidInputException.class);
        }
    }

    @Nested
    @DisplayName("deactivating users")
    class Deactivating {

        @Test
        @DisplayName("a user can be deactivated and reactivated, and doing it twice is harmless")
        void toggles() {
            CouncilUser created = admin();

            assertThat(setActive.execute(council.id(), created.id(), false).active()).isFalse();
            assertThat(setActive.execute(council.id(), created.id(), false).active()).isFalse();
            assertThat(users.findById(created.id()).orElseThrow().active()).isFalse();
            assertThat(setActive.execute(council.id(), created.id(), true).active()).isTrue();
        }

        @Test
        @DisplayName("a user of another council cannot be touched")
        void otherCouncil() {
            CouncilUser created = admin();
            Council other = new CreateCouncil(councils, clock).execute("Kitwe City Council", null);

            assertThatThrownBy(() -> setActive.execute(other.id(), created.id(), false))
                    .isInstanceOf(NotFoundException.class);
            assertThat(users.findById(created.id()).orElseThrow().active()).isTrue();
        }
    }

    @Nested
    @DisplayName("roles")
    class Roles {

        @Test
        @DisplayName("only the officer is read-only, and only the operator has no council")
        void roleFacts() {
            assertThat(Permissions.of(Role.COUNCIL_OFFICER)).containsExactly(Permission.READ);
            assertThat(Permissions.of(Role.COUNCIL_ADMIN)).contains(Permission.MANAGE_USERS);
            assertThat(Role.MSIKA_OPERATOR.belongsToCouncil()).isFalse();
            assertThat(Role.COUNCIL_ADMIN.belongsToCouncil()).isTrue();
        }

        @Test
        @DisplayName("a council role needs a council and the operator must have none")
        void councilMatchesRole() {
            Instant now = clock.instant();

            assertThatThrownBy(() -> new CouncilUser(UUID.randomUUID(), null, "a@example.test", "h", Role.COUNCIL_ADMIN, true, now))
                    .isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> new CouncilUser(UUID.randomUUID(), UUID.randomUUID(), "a@example.test", "h", Role.MSIKA_OPERATOR, true, now))
                    .isInstanceOf(InvalidInputException.class);
        }
    }
}
