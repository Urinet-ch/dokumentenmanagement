package ch.urinet.archiv.ki;

import ch.urinet.archiv.ki.adapter.in.werkzeug.SchreibenderZugriffGegenprobe;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 5.3 (Nachweis: JUnit): Die Werkzeuge des Chat-Agenten und ihre Anwendungsdienste haben keine Nebenwirkungen auf das
 * Archiv. Klassen unter {@code ki.adapter.in.werkzeug} und {@code ki.application.chat} erreichen andere Fachbereiche
 * nur über eine Liste lesender Ports und nur über die dort genannten lesenden Methoden; die einzige Ausnahme ist das
 * Zugriffsprotokoll (E4, entschieden von Fabian am 14.11.2026: {@code dokument_zeigen} schreibt {@code ANZEIGEN} und
 * {@code VERWEIGERT} mit Kanal Chat). Dazu kein Zugriff auf Persistenz-Technik (JPA, Hibernate, JDBC) und auf keinen
 * {@code adapter} eines anderen Fachbereichs. Das Modell kann also kein Werkzeug mit Nebenwirkung aufrufen, weil es keines
 * gibt; was ein Dokumenttext verlangt, bleibt wirkungslos.
 */
@AnalyzeClasses(packages = "ch.urinet.archiv", importOptions = ImportOption.DoNotIncludeTests.class)
class WerkzeugeNurLesendTest {

    static final String[] PAKETE = {"ch.urinet.archiv.ki.adapter.in.werkzeug..", "ch.urinet.archiv.ki.application.chat.."};

    /** Lesende Ports anderer Fachbereiche und die Methoden, die die Werkzeuge aufrufen dürfen. */
    static final Map<String, Set<String>> LESENDE_PORTS = Map.ofEntries(
            Map.entry("ch.urinet.archiv.dokument.application.DokumentRepository", Set.of("finden")),
            Map.entry("ch.urinet.archiv.suche.application.SuchePort", Set.of("suchen")),
            // Hybridsuche der Wortsuche vorgeschaltet (7.1d); liest nur
            Map.entry("ch.urinet.archiv.suche.application.HybridSuche", Set.of("suchen")),
            Map.entry("ch.urinet.archiv.kontakt.application.KontaktRegister", Set.of("aehnlichste", "mitWortanfaengen", "finden")),
            Map.entry("ch.urinet.archiv.dokumentart.application.DokumentartRepository", Set.of("alle")),
            Map.entry("ch.urinet.archiv.mandant.application.MandantKontext", Set.of("mit", "aktuell")),
            Map.entry("ch.urinet.archiv.mandant.application.MandantEinstellungen", Set.of("lesen", "aufbewahrung")),
            // Angebot von kennzahl und offene_posten (7.1b, Spec 05, Req 8.8)
            Map.entry("ch.urinet.archiv.konfiguration.application.Funktionen", Set.of("finanzVerfuegbar")),
            Map.entry("ch.urinet.archiv.wissen.application.WissenLesen",
                    Set.of("index", "seite", "quellenAnzeige", "inhaltDarstellen")),
            Map.entry("ch.urinet.archiv.wissen.application.WissensSuche", Set.of("suchen")),
            Map.entry("ch.urinet.archiv.wissen.application.WissenKonfiguration", Set.of("ingest")),
            // E4: das Zugriffsprotokoll ist der einzige schreibende Port (Anzeige und Verweigerung mit Kanal Chat)
            Map.entry("ch.urinet.archiv.protokoll.application.ZugriffProtokoll", Set.of("schreiben")));

    // Daten- und Fehlertypen ohne Zugriff auf andere Fachbereiche.
    static final Set<String> LESENDE_WERTETYPEN = Set.of(
            "ch.urinet.archiv.sicherheit.application.Verweigerung",
            "ch.urinet.archiv.wissen.application.WissenLesenRepository$IndexZeile");

    private static final Pattern FACHBEREICH = Pattern.compile("^ch\\.urinet\\.archiv\\.([a-z]+)\\.(application|adapter)\\..*$");

    /** Name der äusseren Klasse: Ein verschachtelter Wertetyp eines Ports gehört zu ihm. */
    private static String aussen(String name) {
        int stelle = name.indexOf('$');
        return stelle < 0 ? name : name.substring(0, stelle);
    }

    private static boolean fremd(JavaClass ziel) {
        Matcher m = FACHBEREICH.matcher(ziel.getName());
        return m.matches() && !m.group(1).equals("ki") && !m.group(1).equals("gemeinsam");
    }

    /** Nur die Ports und einzelnen Daten- und Fehlertypen der Listen sowie Domain-Typen sind erreichbar. */
    @ArchTest
    static final ArchRule nurLesendePortsAnderer = classes().that().resideInAnyPackage(PAKETE)
            .should(new ArchCondition<>("andere Fachbereiche nur über lesende Ports erreichen") {
                @Override
                public void check(JavaClass klasse, ConditionEvents ereignisse) {
                    klasse.getDirectDependenciesFromSelf().forEach(d -> {
                        JavaClass ziel = d.getTargetClass();
                        if (fremd(ziel) && !LESENDE_PORTS.containsKey(aussen(ziel.getName()))
                                && !LESENDE_WERTETYPEN.contains(ziel.getName())) {
                            ereignisse.add(SimpleConditionEvent.violated(d, d.getDescription()));
                        }
                    });
                }
            });

    /** Von den erlaubten Ports nur die genannten (lesenden) Methoden; kein {@code anlegen}, {@code aendern} usw. */
    @ArchTest
    static final ArchRule nurLesendeMethoden = classes().that().resideInAnyPackage(PAKETE)
            .should(new ArchCondition<>("von den Ports nur lesende Methoden aufrufen") {
                @Override
                public void check(JavaClass klasse, ConditionEvents ereignisse) {
                    // Aufrufe und Methodenreferenzen (funktionen::finanzVerfuegbar)
                    for (var aufruf : klasse.getCodeUnitAccessesFromSelf()) {
                        String eigentuemer = aufruf.getTargetOwner().getName();
                        // Zugriffsmethoden verschachtelter Wertetypen eines Ports (z. B. Werte.name()) lesen nur
                        Set<String> erlaubt = eigentuemer.contains("$") ? null : LESENDE_PORTS.get(eigentuemer);
                        if (erlaubt != null && !erlaubt.contains(aufruf.getName())) {
                            ereignisse.add(SimpleConditionEvent.violated(aufruf, aufruf.getDescription()));
                        }
                    }
                }
            });

    /** Keine Persistenz-Technik: Zugriff auf Daten nur über Ports ({@code WerkzeugDaten}, {@code ChatSpeicher}). */
    @ArchTest
    static final ArchRule keinePersistenzTechnik = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .that().resideInAnyPackage(PAKETE)
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "java.sql..",
                    "javax.sql..", "io.agroal..", "io.quarkus.hibernate..")
            .because("die Werkzeuge lesen über Ports, nie direkt aus der Datenbank (Req 5.3)");

    @Test
    void einSchreibenderZugriffVerstoesst() {
        JavaClasses klassen = new ClassFileImporter().importClasses(SchreibenderZugriffGegenprobe.class);

        var ergebnis = nurLesendeMethoden.evaluate(klassen);

        assertThat(ergebnis.hasViolation()).isTrue();
        assertThat(ergebnis.getFailureReport().getDetails()).anyMatch(zeile -> zeile.contains("anlegen"));
        // auch der Typ des schreibenden Aufrufs gehört nicht zur Liste
        assertThat(nurLesendePortsAnderer.evaluate(klassen).getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains("NeuesDokument"));
    }

    @Test
    void einFremderAdapterVerstoesst() {
        JavaClasses klassen = new ClassFileImporter().importClasses(SchreibenderZugriffGegenprobe.FremderAdapter.class);

        assertThat(nurLesendePortsAnderer.evaluate(klassen).getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains("DokumentVersionEintrag"));
    }

    @Test
    void direkterZugriffAufWissensRepositoryVerstoesst() {
        JavaClasses klassen = new ClassFileImporter()
                .importClasses(SchreibenderZugriffGegenprobe.WissensRepositoryZugriff.class);

        assertThat(nurLesendePortsAnderer.evaluate(klassen).getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains("WissenLesenRepository"));
    }

    @Test
    void nichtFreigegebeneWissensmethodeVerstoesst() {
        JavaClasses klassen = new ClassFileImporter()
                .importClasses(SchreibenderZugriffGegenprobe.WissensKonfigurationZugriff.class);

        assertThat(nurLesendeMethoden.evaluate(klassen).getFailureReport().getDetails())
                .anyMatch(zeile -> zeile.contains("modell"));
    }

    @Test
    void dieListeEnthaeltNurDasProtokollAlsSchreibendenPort() {
        assertThat(LESENDE_PORTS.entrySet().stream()
                .filter(e -> e.getValue().contains("schreiben")).map(Map.Entry::getKey))
                .containsExactly("ch.urinet.archiv.protokoll.application.ZugriffProtokoll");
    }
}
