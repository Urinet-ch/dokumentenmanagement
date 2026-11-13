package ch.urinet.archiv.gemeinsam.adapter.in.job;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaMethod;
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

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

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
            .should(new ArchCondition<>("Arbeit mit MandantKontext über TaktThread ausführen") {
                @Override
                public void check(JavaMethod methode, ConditionEvents events) {
                    boolean mandant = methode.getOwner().getTransitiveDependenciesFromSelf().stream()
                            .map(Dependency::getTargetClass)
                            .anyMatch(ziel -> ziel.isEquivalentTo(MandantKontext.class));
                    boolean taktThread = methode.getMethodCallsFromSelf().stream()
                            .anyMatch(aufruf -> aufruf.getTargetOwner().isEquivalentTo(TaktThread.class));
                    events.add(new SimpleConditionEvent(methode,
                            !mandant || taktThread || EIGENE_THREADS.contains(methode.getOwner().getName()),
                            methode.getFullName() + " erreicht MandantKontext im Scheduler-Thread"));
                }
            });
}
