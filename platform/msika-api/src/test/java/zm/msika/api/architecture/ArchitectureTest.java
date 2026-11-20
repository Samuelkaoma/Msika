package zm.msika.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Checks the real code (main classes of all Msika modules) against the architecture rules. */
class ArchitectureTest {

    private static JavaClasses msikaClasses;

    @BeforeAll
    static void importClasses() {
        msikaClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("zm.msika");
    }

    @Test
    void coreIsPlainJava() {
        ArchitectureRules.CORE_IS_PLAIN_JAVA.check(msikaClasses);
    }

    @Test
    void ledgerIsPlainJava() {
        ArchitectureRules.LEDGER_IS_PLAIN_JAVA.check(msikaClasses);
    }

    @Test
    void ledgerDoesNotDependUpwards() {
        ArchitectureRules.LEDGER_DOES_NOT_DEPEND_UPWARDS.check(msikaClasses);
    }

    @Test
    void coreDoesNotDependOnApi() {
        ArchitectureRules.CORE_DOES_NOT_DEPEND_ON_API.check(msikaClasses);
    }

    @Test
    void msikaDoesNotImportSimulators() {
        ArchitectureRules.MSIKA_DOES_NOT_IMPORT_SIMULATORS.check(msikaClasses);
    }
}
