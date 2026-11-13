package ch.urinet.archiv;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Table;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regeln aus structure.md «Paketstruktur je Fachbereich», ADR-011 und design.md «Testing Strategy», Zeile
 * «Architektur» (Req 16.13). Geprüft wird nur Produktionscode ({@code DoNotIncludeTests}); nur {@code ArchivMain} im
 * Wurzelpaket ist der Einstieg und von den Schichtregeln ausgenommen, jede andere Klasse dort verstösst.
 * <p>
 * Auslegung: {@code gemeinsam} ist der gemeinsame Kern (Fehler, Problem Details, Zeit, Geld) und darf von jedem
 * Fachbereich in derselben Schicht genutzt werden, hängt selbst aber von keinem Fachbereich ab. Ein Fachbereich nutzt
 * einen anderen über dessen {@code application} (Ports, Use Cases) und die {@code domain}-Typen in deren Signaturen,
 * nie über dessen {@code adapter}.
 */
@AnalyzeClasses(packages = "ch.urinet.archiv", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitekturTest {

    static final String WURZEL = "ch.urinet.archiv";
    static final String GEMEINSAM = "gemeinsam";
    private static final Pattern PAKET = Pattern.compile("^ch\\.urinet\\.archiv\\.([a-z]+)\\.(domain|application|adapter)(\\..*)?$");
    private static final Map<String, Set<String>> ERLAUBT_EIGENER_BEREICH = Map.of(
            "domain", Set.of("domain"),
            "application", Set.of("application", "domain"),
            "adapter", Set.of("adapter", "application", "domain"));
    private static final Map<String, Set<String>> ERLAUBT_FREMDER_BEREICH = Map.of(
            "domain", Set.of(),
            "application", Set.of("application", "domain"),
            "adapter", Set.of("application", "domain"));

    /** {@code domain} hängt nur vom JDK und von Domain-Typen ab: kein Quarkus, Jakarta, JPA, Hibernate oder Jackson. */
    @ArchTest
    static final ArchRule domainOhneFramework = classes().that().resideInAPackage("ch.urinet.archiv..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "ch.urinet.archiv..domain..")
            .because("die Domain ist framework-frei (tech.md Regel 12, structure.md)");

    /**
     * {@code application} hängt nur von {@code domain} (und Ports anderer {@code application}) ab; zulässig sind die
     * Mittel für Use Cases und Ports: CDI, Interceptor-Bindung, JTA, MicroProfile Config und Logging – keine
     * Adapter-Technik (REST, JPA, Vert.x, Quarkus, S3, KI, Jackson).
     */
    @ArchTest
    static final ArchRule applicationNurDomain = classes().that().resideInAPackage("ch.urinet.archiv..application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "ch.urinet.archiv..domain..",
                    "ch.urinet.archiv..application..", "jakarta.enterprise..", "jakarta.inject..",
                    "jakarta.interceptor..", "jakarta.transaction..", "jakarta.annotation..",
                    "org.eclipse.microprofile.config..", "org.jboss.logging..")
            .because("application hängt nur von domain ab (tech.md Regel 12, structure.md)");

    /**
     * Richtung {@code adapter → application → domain} je Fachbereich; Fachbereiche greifen nur über
     * {@code application} (und deren Domain-Typen) aufeinander zu, nie auf einen fremden {@code adapter}.
     */
    @ArchTest
    static final ArchRule schichtenUndFachbereiche = classes().that().resideInAPackage("ch.urinet.archiv..")
            .and().doNotBelongToAnyOf(ArchivMain.class)
            .should(new ArchCondition<>("nur erlaubte Schichten und Fachbereiche nutzen (structure.md)") {
                @Override
                public void check(JavaClass klasse, ConditionEvents ereignisse) {
                    Optional<Ort> quelle = Ort.von(klasse);
                    if (quelle.isEmpty()) {
                        ereignisse.add(SimpleConditionEvent.violated(klasse, klasse.getName()
                                + " liegt nicht unter <fachbereich>.(domain|application|adapter)"));
                        return;
                    }
                    for (Dependency abhaengigkeit : klasse.getDirectDependenciesFromSelf()) {
                        JavaClass ziel = abhaengigkeit.getTargetClass().getBaseComponentType();
                        if (!ziel.getPackageName().startsWith(WURZEL) || ziel.equals(klasse)) {
                            continue;
                        }
                        Optional<Ort> zielOrt = Ort.von(ziel);
                        if (zielOrt.isEmpty() || !quelle.get().darf(zielOrt.get())) {
                            ereignisse.add(SimpleConditionEvent.violated(abhaengigkeit, abhaengigkeit.getDescription()));
                        }
                    }
                }
            });

    /**
     * Driven Ports liegen in {@code application}, ihre Implementierungen und Dev-Stubs unter {@code adapter/out}:
     * Eine Klasse unter {@code adapter.out} implementiert nur Ports aus {@code application}, und wer einen Port aus
     * {@code application} implementiert, liegt in {@code application} (Use Case) oder unter {@code adapter.out}.
     */
    @ArchTest
    static final ArchRule adapterOutImplementiertPorts = classes().that().resideInAPackage("ch.urinet.archiv..adapter.out..")
            .and().areNotInterfaces()
            .should(new ArchCondition<>("nur Ports aus application implementieren") {
                @Override
                public void check(JavaClass klasse, ConditionEvents ereignisse) {
                    klasse.getRawInterfaces().stream()
                            .filter(s -> s.getPackageName().startsWith(WURZEL + "."))
                            .filter(s -> !s.getPackageName().matches("^ch\\.urinet\\.archiv\\.[a-z]+\\.application(\\..*)?$"))
                            .forEach(s -> ereignisse.add(SimpleConditionEvent.violated(klasse, klasse.getName()
                                    + " implementiert " + s.getName() + ", das nicht in application liegt")));
                }
            });

    @ArchTest
    static final ArchRule portsUnterAdapterOutImplementiert = classes()
            .that(implementierenPortAusApplication())
            .should().resideInAnyPackage("ch.urinet.archiv..application..", "ch.urinet.archiv..adapter.out..")
            .because("Implementierungen der Driven Ports liegen unter adapter/out (structure.md)");

    @ArchTest
    static final ArchRule stubsUnterAdapterOut = classes().that().haveSimpleNameContaining("Stub")
            .and().resideInAPackage("ch.urinet.archiv..")
            .should().resideInAPackage("ch.urinet.archiv..adapter.out..")
            .because("Dev-Stubs sind Adapter von Driven Ports (structure.md)");

    /** Beträge sind nie {@code double} oder {@code float} (tech.md Regel 9). */
    @ArchTest
    static final ArchRule betraegeNieGleitkomma = fields().that().haveNameMatching(Betrag.NAME)
            .should().notHaveRawType(double.class).andShould().notHaveRawType(Double.class)
            .andShould().notHaveRawType(float.class).andShould().notHaveRawType(Float.class)
            .allowEmptyShould(true)
            .because("Beträge sind BigDecimal (tech.md Regel 9)");

    @ArchTest
    static final ArchRule betragsMethodenNieGleitkomma = methods().that().haveNameMatching(Betrag.NAME)
            .should(new ArchCondition<>("keine Gleitkommazahl liefern") {
                @Override
                public void check(JavaMethod methode, ConditionEvents ereignisse) {
                    if (Betrag.GLEITKOMMA.contains(methode.getRawReturnType().getName())) {
                        ereignisse.add(SimpleConditionEvent.violated(methode, methode.getFullName()
                                + " liefert " + methode.getRawReturnType().getName()));
                    }
                }
            })
            .allowEmptyShould(true)
            .because("Beträge sind BigDecimal (tech.md Regel 9)");

    /**
     * Secrets nur aus Dateien unter {@code /run/secrets/}, nie aus Umgebungsvariablen (tech.md Regel 7); erfasst Aufrufe
     * und Methodenreferenzen ({@code System::getenv}).
     */
    @ArchTest
    static final ArchRule keineUmgebungsvariablen = noClasses().should().accessTargetWhere(
                    DescribedPredicate.describe("System.getenv", zugriff -> zugriff.getTarget().getOwner()
                            .isEquivalentTo(System.class) && zugriff.getTarget().getName().equals("getenv")))
            .because("Secrets liegen nur als Dateien unter /run/secrets/ (tech.md Regel 7)");

    /** Die Anwendung setzt nie {@code bypassGovernanceRetention} (tech.md Regel 2, Req 6.5). */
    @ArchTest
    static final ArchRule keinBypassGovernance = noClasses().should().accessTargetWhere(
                    DescribedPredicate.describe("bypassGovernanceRetention",
                            zugriff -> zugriff.getTarget().getName().equals("bypassGovernanceRetention")))
            .because("die Anwendung verkürzt nie eine Sperre (tech.md Regel 2, Req 6.5)");

    /**
     * Uploads und abgelegte Dateien laufen als Strom oder über temporäre Dateien, nie ganz im Heap (Speicherbudget
     * Stufe A, design.md «Umsetzung 2.4»; Review PR #53, K1), ebenso der Jahresexport (Spec 02, Req 7.1). Die Stubs
     * für dev und Tests sind ausgenommen.
     */
    @ArchTest
    static final ArchRule dateienNieGanzImHeap = noClasses()
            .that().resideInAnyPackage("ch.urinet.archiv.dokument..", "ch.urinet.archiv.ablage..",
                    "ch.urinet.archiv.export..")
            .and().resideOutsideOfPackage("ch.urinet.archiv..adapter.out.stub..")
            .should().accessTargetWhere(DescribedPredicate.describe("ganze Datei als byte[]",
                    zugriff -> Set.of("readAllBytes", "toByteArray", "getObjectAsBytes", "asByteArray")
                            .contains(zugriff.getTarget().getName())))
            .because("Dateien bis 50 MB je Stück dürfen den Heap nicht füllen (Speicherbudget Stufe A, tech.md)");

    /**
     * Code eines Mandanten greift auf Tabellen in {@code public} nur über {@code auftrag} zu (ADR-002): Entitäten mit
     * {@code schema = "public"} gibt es nur für {@code auftrag} – ausser im Mandantenregister ({@code mandant}) und
     * für die API-Clients ({@code sicherheit}).
     */
    @ArchTest
    static final ArchRule publicNurUeberAuftrag = classes().that().areAnnotatedWith(Table.class)
            .should(new ArchCondition<>("auf public nur über auftrag zugreifen") {
                @Override
                public void check(JavaClass klasse, ConditionEvents ereignisse) {
                    Table tabelle = klasse.getAnnotationOfType(Table.class);
                    if (!"public".equalsIgnoreCase(tabelle.schema())) {
                        return;
                    }
                    boolean erlaubt = "auftrag".equals(tabelle.name())
                            || "mandant".equals(tabelle.name()) && klasse.getPackageName().startsWith(WURZEL + ".mandant.")
                            || "api_client".equals(tabelle.name())
                            && klasse.getPackageName().startsWith(WURZEL + ".sicherheit.");
                    if (!erlaubt) {
                        ereignisse.add(SimpleConditionEvent.violated(klasse, klasse.getName() + " bildet public."
                                + tabelle.name() + " ab"));
                    }
                }
            })
            .allowEmptyShould(true);

    /** Probe-Endpunkte aus Aufgabe 1.4 liegen nur unter src/test und gelangen nie ins Produktionsartefakt. */
    @ArchTest
    static final ArchRule probeNurInTests = noClasses().should().resideInAPackage("ch.urinet.archiv.probe..")
            .orShould().haveSimpleNameStartingWith("Probe");

    /**
     * Aufträge entstehen nur über den Anwendungsfall {@code AuftragService} und damit nur mit einer geprüften
     * {@code Nutzlast} (Aufgabe 2.5); der JDBC-Weg zu {@code public.auftrag} liegt nur im Adapter der Verarbeitung.
     * Auslegung: Geprüft werden die Aufrufer von {@code AuftragSchlange.einfuegen} und die Nutzer von
     * {@code JdbcAuftragSchlange}; dass kein anderer Code per SQL in {@code public.auftrag} schreibt, kann ArchUnit
     * nicht sehen.
     */
    @ArchTest
    static final ArchRule auftraegeNurUeberNutzlast = noClasses()
            .that().doNotHaveFullyQualifiedName(WURZEL + ".verarbeitung.application.AuftragService")
            .should().callMethodWhere(DescribedPredicate.describe("AuftragSchlange.einfuegen", aufruf ->
                    aufruf.getTarget().getName().equals("einfuegen")
                            && aufruf.getTarget().getOwner().isAssignableTo(WURZEL
                            + ".verarbeitung.application.AuftragSchlange")))
            .because("ein Auftrag entsteht nur über AuftragService.einreihen mit einer Nutzlast (Aufgabe 2.5)");

    @ArchTest
    static final ArchRule auftragJdbcNurImAdapter = noClasses()
            .that().resideOutsideOfPackage(WURZEL + ".verarbeitung.adapter.out.persistence..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName(WURZEL + ".verarbeitung.adapter.out.persistence.JdbcAuftragSchlange")
            .because("der JDBC-Weg zu public.auftrag liegt nur im Adapter der Verarbeitung (Aufgabe 2.5)");

    /**
     * Die Seed-Frist ({@code archiv.dokumentart.seed-zeitgrenze}, 2 s) gilt nur für den Seed beim Anlegen eines
     * Mandanten (Admin-Kommando, Quelle im Repo), nie für eine Eingabe über die API (Review PR #81, S3): Nur
     * {@code MandantProvisionierung} ruft {@code SeedPruefung.pruefen}, nur {@code SeedPruefung} ruft
     * {@code DokumentartPruefung.seedPruefen}, und nur diese Methode ruft {@code ZusatzSchemaPruefer.seedSchemaPruefen}.
     * Auslegung: Eine Konfigurationsdatei über die API (Spec 05, Req 6) ist keine Seed-Quelle; wer den Seed-Weg dafür
     * nutzen will, muss diese Regel bewusst ändern.
     */
    @ArchTest
    static final ArchRule seedFristNurImSeed = noClasses().should().accessTargetWhere(DescribedPredicate.describe(
                    "den Seed-Weg ausserhalb des Seeds", zugriff -> seedZugriffVerboten(
                            zugriff.getOrigin().getOwner().getName(), zugriff.getOrigin().getName(),
                            zugriff.getTarget().getOwner(), zugriff.getTarget().getName())))
            .because("die Seed-Frist gilt nur für den Seed eines Mandanten (Spec 01 design.md «Umsetzung 2.1»)");

    private static boolean seedZugriffVerboten(String herkunft, String herkunftMethode, JavaClass ziel, String methode) {
        String pruefung = WURZEL + ".dokumentart.application.DokumentartPruefung";
        String seed = WURZEL + ".mandant.application.SeedPruefung";
        if (methode.equals("seedSchemaPruefen") && ziel.isAssignableTo(WURZEL + ".dokumentart.application.ZusatzSchemaPruefer")) {
            return !(herkunft.equals(pruefung) && herkunftMethode.equals("seedPruefen"));
        }
        if (methode.equals("seedPruefen") && ziel.isAssignableTo(pruefung)) {
            return !herkunft.equals(seed);
        }
        if (methode.equals("pruefen") && ziel.isAssignableTo(seed)) {
            return !herkunft.equals(WURZEL + ".mandant.application.MandantProvisionierung");
        }
        return false;
    }

    private static DescribedPredicate<JavaClass> implementierenPortAusApplication() {
        return DescribedPredicate.describe("einen Port aus application implementieren", klasse -> !klasse.isInterface()
                && klasse.getAllRawInterfaces().stream()
                .anyMatch(s -> s.getPackageName().matches("^ch\\.urinet\\.archiv\\.[a-z]+\\.application(\\..*)?$")
                        && !s.isAnnotation()));
    }

    /** Fachbereich und Schicht einer Klasse. */
    record Ort(String bereich, String schicht) {

        static Optional<Ort> von(JavaClass klasse) {
            Matcher treffer = PAKET.matcher(klasse.getPackageName());
            return treffer.matches() ? Optional.of(new Ort(treffer.group(1), treffer.group(2))) : Optional.empty();
        }

        boolean darf(Ort ziel) {
            if (ziel.bereich.equals(bereich) || ziel.bereich.equals(GEMEINSAM)) {
                return ERLAUBT_EIGENER_BEREICH.get(schicht).contains(ziel.schicht);
            }
            return !bereich.equals(GEMEINSAM) && ERLAUBT_FREMDER_BEREICH.get(schicht).contains(ziel.schicht);
        }
    }

    private static final class Betrag {
        static final String NAME = "(?i).*(betrag|summe|preis|total|mwst|saldo).*";
        static final Set<String> GLEITKOMMA = Set.of("double", "float", Double.class.getName(), Float.class.getName());

        private Betrag() {
        }
    }
}
