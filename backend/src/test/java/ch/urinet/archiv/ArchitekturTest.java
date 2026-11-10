package ch.urinet.archiv;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Rauchregel für ArchUnit in {@code mvn verify}; die Regeln aus ADR-011 folgen mit Aufgabe 1.6 (Req 16.13). */
@AnalyzeClasses(packages = "ch.urinet.archiv", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitekturTest {

    @ArchTest
    static final ArchRule domainOhneFramework = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("io.quarkus..", "jakarta..", "com.fasterxml.jackson..", "org.hibernate..")
            .allowEmptyShould(true);

    /** Probe-Endpunkte aus Aufgabe 1.4 liegen nur unter src/test und gelangen nie ins Produktionsartefakt. */
    @ArchTest
    static final ArchRule probeNurInTests = noClasses().should().resideInAPackage("ch.urinet.archiv.probe..")
            .orShould().haveSimpleNameStartingWith("Probe");
}
