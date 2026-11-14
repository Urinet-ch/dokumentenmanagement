package ch.urinet.archiv.gemeinsam.adapter.in.job;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import ch.urinet.archiv.mandant.application.MandantKontext;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geplante Jobs greifen erst nach der Migration der Mandanten-Schemas auf Mandanten-Daten zu (design.md «Mandant»,
 * «Start und Migration»): Jeder {@code @Scheduled}-Takt überspringt sich bis dahin über {@link VorMigrationUeberspringen},
 * und keine Klasse mit geplantem Job holt beim Start über {@link StartupEvent} etwas nach. Ausgenommen ist nur, wer
 * keine Mandanten-Daten liest. Ein Takt, der (auch über Dritte) {@link MandantKontext} erreicht, arbeitet in einem
 * eigenen Thread ohne den Request-Kontext des Schedulers ({@link TaktThread}; Befund prod 13.11.2026, Spec 02
 * design.md «Umsetzung (Aufgabe 6.1)»).
 */
@AnalyzeClasses(packages = "ch.urinet.archiv", importOptions = ImportOption.DoNotIncludeTests.class)
class GeplanteJobsTest {

    /** Wächter der Keycloak-Bereitschaft: liest keine Mandanten-Daten und soll die Selbstheilung nicht verzögern. */
    private static final String OIDC_WAECHTER = "ch.urinet.archiv.gemeinsam.adapter.in.health.OidcBereitschaftWaechter";

    /** Start-Beobachter in Klassen mit geplantem Job, die keine Mandanten-Daten lesen. */
    private static final Set<String> START_OHNE_MANDANTEN_DATEN =
            Set.of("ch.urinet.archiv.verarbeitung.adapter.in.job.AuftragPoller.beimStart");

    /**
     * Takte, deren Mandanten-Arbeit der {@code AuftragArbeiter} selbst in eigenen Threads ohne Request-Kontext startet
     * (Handler und Hooks nach dem letzten Fehlversuch).
     */
    private static final Set<String> EIGENE_THREADS = Set.of(
            "ch.urinet.archiv.verarbeitung.adapter.in.job.AuftragPoller",
            "ch.urinet.archiv.verarbeitung.adapter.in.job.AuftragAufraeumer");

    /** Nur Implementierungen eigener Typen zählen; die fremder ({@code Runnable}, {@code Supplier}) nicht. */
    private static final String EIGENE_PAKETE = "ch.urinet.archiv.";

    private static final DescribedPredicate<JavaMethod> GEPLANT =
            DescribedPredicate.describe("@Scheduled", m -> m.isAnnotatedWith(Scheduled.class));

    @ArchTest
    static final ArchRule jobsWartenAufMigration = methods().that().areAnnotatedWith(Scheduled.class)
            .and().areDeclaredInClassesThat().doNotHaveFullyQualifiedName(OIDC_WAECHTER)
            .should(new ArchCondition<>("bis zur Migration übersprungen werden (skipExecutionIf = VorMigrationUeberspringen)") {
                @Override
                public void check(JavaMethod methode, ConditionEvents events) {
                    Class<?> praedikat = methode.reflect().getAnnotation(Scheduled.class).skipExecutionIf();
                    events.add(new SimpleConditionEvent(methode, praedikat == VorMigrationUeberspringen.class,
                            methode.getFullName() + " hat skipExecutionIf=" + praedikat.getSimpleName()));
                }
            });

    @ArchTest
    static final ArchRule jobsHolenNichtBeimStartNach = methods()
            .that().areDeclaredInClassesThat().containAnyMethodsThat(GEPLANT)
            .and().areDeclaredInClassesThat().doNotHaveFullyQualifiedName(OIDC_WAECHTER)
            .should(new ArchCondition<>("nicht StartupEvent beobachten (Nachholen über SchemasBereit)") {
                @Override
                public void check(JavaMethod methode, ConditionEvents events) {
                    boolean startBeobachter = methode.getParameters().stream()
                            .anyMatch(p -> p.getRawType().isEquivalentTo(StartupEvent.class)
                                    && p.isAnnotatedWith(Observes.class));
                    String name = methode.getOwner().getName() + "." + methode.getName();
                    events.add(new SimpleConditionEvent(methode,
                            !startBeobachter || START_OHNE_MANDANTEN_DATEN.contains(name),
                            methode.getFullName() + " beobachtet StartupEvent"));
                }
            });

    @ArchTest
    static final ArchRule mandantenTakteImEigenenThread = methods().that().areAnnotatedWith(Scheduled.class)
            .should(arbeitMitMandantKontextUeberTaktThread());

    /**
     * Ein Takt erreicht {@link MandantKontext}, wenn ein Aufruf oder eine Methodenreferenz aus der {@code @Scheduled}-
     * Methode, auch über weitere Aufrufe, eine Methode von {@code MandantKontext} trifft. Ein Aufruf über ein eigenes
     * Interface (Port) oder eine überschreibbare Methode führt auch zu den Implementierungen in Unterklassen, denn zur
     * Laufzeit läuft der Adapter (Review PR #130, z. B. {@code DbFestgehalteneManifeste implements
     * FestgehalteneManifeste}). Gefolgt wird Aufrufen, nicht Klassenabhängigkeiten: Eine Konstante oder statische
     * Hilfsmethode einer Klasse macht deren Kollaborateure nicht erreichbar (sonst träfe es {@code UeberwachungsTakt}
     * über {@code ManifestErzeugen.TYP}).
     */
    static ArchCondition<JavaMethod> arbeitMitMandantKontextUeberTaktThread() {
        return new ArchCondition<>("Arbeit mit MandantKontext (auch über Ports) über TaktThread ausführen") {
            @Override
            public void check(JavaMethod methode, ConditionEvents events) {
                boolean taktThread = methode.getMethodCallsFromSelf().stream()
                        .anyMatch(aufruf -> aufruf.getTargetOwner().isEquivalentTo(TaktThread.class));
                events.add(new SimpleConditionEvent(methode,
                        !erreichtMandantKontext(methode) || taktThread
                                || EIGENE_THREADS.contains(methode.getOwner().getName()),
                        methode.getFullName() + " erreicht MandantKontext im Scheduler-Thread"));
            }
        };
    }

    private static boolean erreichtMandantKontext(JavaCodeUnit start) {
        Set<JavaCodeUnit> gesehen = new HashSet<>(Set.of(start));
        Deque<JavaCodeUnit> offen = new ArrayDeque<>(gesehen);
        while (!offen.isEmpty()) {
            JavaCodeUnit einheit = offen.pop();
            List<CodeUnitAccessTarget> ziele = Stream.concat(einheit.getCallsFromSelf().stream(),
                    einheit.getCodeUnitReferencesFromSelf().stream()).<CodeUnitAccessTarget>map(z -> z.getTarget())
                    .toList();
            for (CodeUnitAccessTarget ziel : ziele) {
                if (ziel.getOwner().isEquivalentTo(MandantKontext.class)) {
                    return true;
                }
                Stream.concat(ziel.resolveMember().stream(), implementierungen(ziel))
                        .filter(gesehen::add).forEach(offen::push);
            }
        }
        return false;
    }

    /** Überschreibungen in Unterklassen bzw. Implementierungen; nur eigene Typen, nicht {@code Runnable} & Co. */
    private static Stream<JavaCodeUnit> implementierungen(CodeUnitAccessTarget ziel) {
        if (!ziel.getOwner().getPackageName().startsWith(EIGENE_PAKETE)) {
            return Stream.empty();
        }
        List<String> parameter = ziel.getRawParameterTypes().stream().map(JavaClass::getName).toList();
        return ziel.getOwner().getAllSubclasses().stream()
                .flatMap(unterklasse -> unterklasse.tryGetCodeUnitWithParameterTypeNames(ziel.getName(), parameter)
                        .stream());
    }

    /** Gegenprobe: Ein Takt, der Mandanten-Arbeit nur über einen Port erreicht, fällt ohne TaktThread durch. */
    @Test
    void gegenprobeUeberPort() {
        JavaClasses klassen = new ClassFileImporter().importClasses(Port.class, PortAdapter.class, TaktUeberPort.class,
                TaktUeberPortImEigenenThread.class, MandantKontext.class, TaktThread.class);

        // die Proben tragen kein @Scheduled, sonst würde Quarkus sie in den Szenarien als Takte einplanen
        assertThat(methods().that().haveName("takt").should(arbeitMitMandantKontextUeberTaktThread())
                .evaluate(klassen).getFailureReport().getDetails())
                .singleElement().asString().contains(TaktUeberPort.class.getName() + ".takt()");
    }

    interface Port {
        void arbeiten();
    }

    static class PortAdapter implements Port {
        MandantKontext kontext;

        @Override
        public void arbeiten() {
            kontext.mit(null, () -> null);
        }
    }

    static class TaktUeberPort {
        Port port;

        void takt() {
            port.arbeiten();
        }
    }

    static class TaktUeberPortImEigenenThread {
        Port port;

        void takt() {
            TaktThread.ausfuehren("probe", port::arbeiten);
        }
    }
}
