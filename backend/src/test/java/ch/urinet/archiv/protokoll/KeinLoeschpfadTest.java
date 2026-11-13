package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.dokument.adapter.out.persistence.DokumentVersionEintrag;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 10.9 (Nachweis: JUnit, kein Löschpfad): Der Code des Archivs ändert, löscht oder leert weder das
 * Zugriffsprotokoll noch die Versionen noch das Benutzerverzeichnis. Trigger und Rechte prüft
 * {@code MandantEigentuemerTest}; dieser Test durchsucht Java-Quellen und Changesets nach UPDATE, DELETE und TRUNCATE
 * auf diesen Tabellen, ausgenommen die Trigger- und REVOKE-Anweisungen ({@code … on <tabelle>}).
 * <p>
 * Dazu die Wege über JPA/Panache (Review PR #88, S5): Entities auf diesen Tabellen (heute {@code DokumentVersionEintrag})
 * tragen {@code @Immutable}, damit Dirty Checking kein UPDATE schreibt; kein Code ruft {@code delete*} oder
 * {@code update*} auf ihnen auf (Bytecode, auch Instanzmethoden), und kein HQL-Text löscht oder ändert über ihren
 * Entity-Namen. Nicht erkennbar ist {@code EntityManager.remove(objekt)}, weil der Typ des Arguments im Bytecode fehlt;
 * dort greifen zur Laufzeit Trigger und Rechte.
 */
class KeinLoeschpfadTest {

    private static final Path MAIN = Path.of("src/main");
    private static final Set<String> TABELLEN = Set.of("zugriff_protokoll", "dokument_version", "benutzer_verzeichnis");
    private static final Pattern LOESCHPFAD = Pattern.compile("(?is)\\b(update|delete\\s+from|truncate)\\b[^;]{0,80}?"
            + "\\b(zugriff_protokoll|dokument_version|benutzer_verzeichnis)\\b");
    private static final Pattern KOMMENTAR = Pattern.compile("(?s)/\\*.*?\\*/|<!--.*?-->|//[^\\n]*");
    private static final Pattern TRIGGER_ODER_RECHT = Pattern.compile("(?is)\\bon\\s+(table\\s+)?[\\w.\"+ ]*$");
    private static final Pattern AENDERNDE_METHODE = Pattern.compile("^(delete|update)\\w*$");

    @Test
    void keinCodeAendertLoeschtOderLeertDieProtokolle() throws IOException {
        Set<String> entities = entities(archiv()).stream().map(JavaClass::getSimpleName).collect(Collectors.toSet());
        List<String> funde = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(MAIN)) {
            for (Path datei : dateien.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".xml")
                            || p.toString().endsWith(".sql")).toList()) {
                funde.addAll(funde(datei.toString(), Files.readString(datei, StandardCharsets.UTF_8), entities));
            }
        }
        assertThat(funde).isEmpty();
    }

    @Test
    void entitiesDerProtokolleSindUnveraenderlichUndWerdenNieGeloeschtOderGeaendert() {
        JavaClasses archiv = archiv();
        Set<JavaClass> entities = entities(archiv);
        assertThat(entities).extracting(JavaClass::getName).contains(DokumentVersionEintrag.class.getName());
        assertThat(entities).allSatisfy(e -> assertThat(e.isAnnotatedWith(Immutable.class))
                .as("%s ohne @Immutable", e.getSimpleName()).isTrue());
        assertThat(jpaFunde(archiv, entities)).isEmpty();
    }

    @Test
    void findetLoeschpfadeAuchMitSchemaAusVariable() {
        Set<String> entities = Set.of("DokumentVersionEintrag");
        assertThat(funde("Probe.java", "String sql = \"delete from \" + schema + \".zugriff_protokoll where id = ?\";",
                entities)).hasSize(1);
        assertThat(funde("Probe.java", "befehl.execute(\"update \" + s + \".benutzer_verzeichnis set name = ?\");",
                entities)).hasSize(1);
        assertThat(funde("probe.xml", "<sql>truncate dokument_version;</sql>", entities)).hasSize(1);
        assertThat(funde("probe.xml", "create trigger t before update or delete on zugriff_protokoll for each row",
                entities)).isEmpty();
        assertThat(funde("Probe.java", "\"revoke update, delete, truncate on \" + schema + \".benutzer_verzeichnis\"",
                entities)).isEmpty();
    }

    /** Gegenprobe zu JPA/Panache: HQL über den Entity-Namen und Aufrufe von delete/update im Bytecode. */
    @Test
    void findetLoeschpfadeUeberJpaUndPanache() {
        Set<String> entities = Set.of("DokumentVersionEintrag");
        assertThat(funde("Probe.java", "em.createQuery(\"delete from DokumentVersionEintrag where id = :id\")",
                entities)).hasSize(1);
        assertThat(funde("Probe.java", "em.createQuery(\"update DokumentVersionEintrag v set v.grund = null\")",
                entities)).hasSize(1);
        assertThat(funde("Probe.java", "DokumentVersionEintrag.delete(\"dokumentId\", id);", entities)).hasSize(1);
        assertThat(funde("Probe.java", "DokumentVersionEintrag.find(\"dokumentId\", id).list();", entities)).isEmpty();

        JavaClasses probe = new ClassFileImporter().importClasses(LoeschpfadUeberPanache.class,
                DokumentVersionEintrag.class);
        assertThat(jpaFunde(probe, entities(probe))).hasSize(3)
                .anySatisfy(f -> assertThat(f).contains(".deleteAll"))
                .anySatisfy(f -> assertThat(f).contains(".delete"))
                .anySatisfy(f -> assertThat(f).contains(".update"));
    }

    private static List<String> funde(String name, String inhalt, Set<String> entities) {
        String ohneKommentare = KOMMENTAR.matcher(inhalt).replaceAll(" ");
        List<String> funde = new ArrayList<>();
        Matcher treffer = LOESCHPFAD.matcher(ohneKommentare);
        while (treffer.find()) {
            String vorTabelle = ohneKommentare.substring(treffer.start(), treffer.start(2));
            if (!TRIGGER_ODER_RECHT.matcher(vorTabelle).find()) {
                funde.add(name + ": " + treffer.group().replaceAll("\\s+", " "));
            }
        }
        for (String entity : entities) {
            Matcher jpa = Pattern.compile("(?is)\\b(update|delete(\\s+from)?)\\s+" + entity + "\\b|\\b" + entity
                    + "\\s*\\.\\s*(delete|update)\\w*\\s*\\(").matcher(ohneKommentare);
            while (jpa.find()) {
                funde.add(name + ": " + jpa.group().replaceAll("\\s+", " "));
            }
        }
        return funde;
    }

    private static List<String> jpaFunde(JavaClasses klassen, Set<JavaClass> entities) {
        Set<String> namen = entities.stream().map(JavaClass::getName).collect(Collectors.toSet());
        List<String> funde = new ArrayList<>();
        for (JavaClass klasse : klassen) {
            for (JavaMethodCall aufruf : klasse.getMethodCallsFromSelf()) {
                if (namen.contains(aufruf.getTargetOwner().getName())
                        && AENDERNDE_METHODE.matcher(aufruf.getName()).matches()) {
                    funde.add(aufruf.getDescription());
                }
            }
        }
        return funde;
    }

    private static Set<JavaClass> entities(JavaClasses klassen) {
        return klassen.stream().filter(k -> k.isAnnotatedWith(Entity.class))
                .filter(k -> k.tryGetAnnotationOfType(Table.class).map(t -> TABELLEN.contains(t.name())).orElse(false))
                .collect(Collectors.toSet());
    }

    private static JavaClasses archiv() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ch.urinet.archiv");
    }

    /** Nur für die Gegenprobe; wird nie ausgeführt. */
    @SuppressWarnings("unused")
    static final class LoeschpfadUeberPanache {

        void loeschen(DokumentVersionEintrag version) {
            DokumentVersionEintrag.deleteAll();
            version.delete();
            DokumentVersionEintrag.update("grund = null where id = ?1", version.id);
        }
    }
}
