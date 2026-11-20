package zm.msika.api.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves each rule bites. The classes in the four {@code fixture} packages (test sources only) break
 * the rules on purpose. If a rule had a typo in a package name, or silently matched nothing, the
 * matching test here would fail.
 */
class RulesCanFailTest {

    private static JavaClasses fixtures;

    @BeforeAll
    static void importFixtures() {
        fixtures = new ClassFileImporter().importPackages(
                "zm.msika.core.fixture",
                "zm.msika.ledger.fixture",
                "zm.msika.api.fixture",
                "zm.msika.zamsim.fixture");
    }

    private static void assertViolated(ArchRule rule) {
        assertThatThrownBy(() -> rule.check(fixtures)).isInstanceOf(AssertionError.class);
    }

    @Test
    void coreImportingSpringOrAnHttpClientIsCaught() {
        assertViolated(ArchitectureRules.CORE_IS_PLAIN_JAVA);
    }

    @Test
    void ledgerImportingSpringIsCaught() {
        assertViolated(ArchitectureRules.LEDGER_IS_PLAIN_JAVA);
    }

    @Test
    void ledgerDependingOnCoreIsCaught() {
        assertViolated(ArchitectureRules.LEDGER_DOES_NOT_DEPEND_UPWARDS);
    }

    @Test
    void coreDependingOnApiIsCaught() {
        assertViolated(ArchitectureRules.CORE_DOES_NOT_DEPEND_ON_API);
    }

    @Test
    void msikaImportingASimulatorIsCaught() {
        assertViolated(ArchitectureRules.MSIKA_DOES_NOT_IMPORT_SIMULATORS);
    }
}
