package zm.msika.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * The dependency rules from ARCHITECTURE.md section 3 and AGENTS.md rules 6 and 7.
 *
 * <p>The rules are constants so {@link ArchitectureTest} can check the real code and
 * {@link RulesCanFailTest} can prove each one fails on bad code. A rule that cannot fail is useless,
 * and these rules pass vacuously while a module is still empty.
 */
final class ArchitectureRules {

    private ArchitectureRules() {}

    /** Frameworks and clients that msika-core and msika-ledger must never import (rule 7). */
    private static final String[] FORBIDDEN_IN_PLAIN_JAVA = {
        "org.springframework..",
        "jakarta.persistence..",
        "javax.persistence..",
        "org.hibernate..",
        "com.fasterxml.jackson..",
        "tools.jackson..",
        "java.net.http..",
        "org.apache.hc..",
        "org.apache.http..",
        "okhttp3..",
        "jakarta.servlet..",
        "jakarta.ws.rs..",
    };

    static final ArchRule CORE_IS_PLAIN_JAVA = noClasses()
            .that().resideInAPackage("zm.msika.core..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_IN_PLAIN_JAVA)
            .allowEmptyShould(true)
            .as("msika-core imports no Spring, JPA, Jackson or HTTP clients");

    static final ArchRule LEDGER_IS_PLAIN_JAVA = noClasses()
            .that().resideInAPackage("zm.msika.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_IN_PLAIN_JAVA)
            .allowEmptyShould(true)
            .as("msika-ledger imports no Spring, JPA, Jackson or HTTP clients");

    /** Dependency direction: msika-api, then msika-core, then msika-ledger. Never upwards. */
    static final ArchRule LEDGER_DOES_NOT_DEPEND_UPWARDS = noClasses()
            .that().resideInAPackage("zm.msika.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage("zm.msika.core..", "zm.msika.api..")
            .allowEmptyShould(true)
            .as("msika-ledger does not depend on msika-core or msika-api");

    static final ArchRule CORE_DOES_NOT_DEPEND_ON_API = noClasses()
            .that().resideInAPackage("zm.msika.core..")
            .should().dependOnClassesThat().resideInAPackage("zm.msika.api..")
            .allowEmptyShould(true)
            .as("msika-core does not depend on msika-api");

    /** Rule 6: Msika never imports code from the simulators. It talks to them over HTTP only. */
    static final ArchRule MSIKA_DOES_NOT_IMPORT_SIMULATORS = noClasses()
            .that().resideInAnyPackage("zm.msika.api..", "zm.msika.core..", "zm.msika.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage("zm.msika.zamsim..", "zm.msika.elevysim..")
            .allowEmptyShould(true)
            .as("Msika code never imports zamsim or elevysim");
}
