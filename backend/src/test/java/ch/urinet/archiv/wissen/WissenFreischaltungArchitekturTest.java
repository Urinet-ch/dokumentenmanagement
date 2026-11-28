package ch.urinet.archiv.wissen;

import ch.urinet.archiv.wissen.adapter.in.kommando.WissenFreischaltungKommando;
import ch.urinet.archiv.wissen.application.WissenFreischalten;
import ch.urinet.archiv.wissen.application.WissensfreischaltungPort;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Spec 04, Req 22.3: Den Zustand «Wissen freigeschaltet» ändert nur das Betreiber-Kommando. Kein REST-Endpunkt, kein
 * Auftrag, keine Konfigurationsdatei und kein Einstellungskatalog ruft den Anwendungsfall oder den Port zum Setzen auf
 * (die Migration nach 22.1 ist ein Changeset, kein Programmcode).
 */
@AnalyzeClasses(packages = "ch.urinet.archiv", importOptions = ImportOption.DoNotIncludeTests.class)
class WissenFreischaltungArchitekturTest {

    @ArchTest
    static final ArchRule nurDasKommandoSchaltet = noClasses()
            .that().doNotHaveFullyQualifiedName(WissenFreischaltungKommando.class.getName())
            .should().callMethodWhere(aufruf(WissenFreischalten.class, "setzen"))
            .because("Req 22.3: nur «archiv mandant wissen <code> ein|aus» ändert den Zustand");

    @ArchTest
    static final ArchRule nurDerAnwendungsfallSetzt = noClasses()
            .that().doNotHaveFullyQualifiedName(WissenFreischalten.class.getName())
            .should().callMethodWhere(aufruf(WissensfreischaltungPort.class, "setzen"))
            .because("Req 22.3: der Port wird nur über den Anwendungsfall des Kommandos geschrieben");

    private static DescribedPredicate<JavaMethodCall> aufruf(Class<?> typ, String methode) {
        return DescribedPredicate.describe(typ.getSimpleName() + "." + methode,
                a -> a.getTarget().getName().equals(methode) && a.getTarget().getOwner().isAssignableTo(typ));
    }
}
