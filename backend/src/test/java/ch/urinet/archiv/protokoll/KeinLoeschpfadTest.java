package ch.urinet.archiv.protokoll;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 10.9 (Nachweis: JUnit, kein Löschpfad): Der Code des Archivs ändert, löscht oder leert weder das
 * Zugriffsprotokoll noch die Versionen noch das Benutzerverzeichnis. Trigger und Rechte prüft
 * {@code MandantEigentuemerTest}; dieser Test durchsucht Java-Quellen und Changesets nach UPDATE, DELETE und TRUNCATE
 * auf diesen Tabellen, ausgenommen die Trigger- und REVOKE-Anweisungen ({@code … on <tabelle>}).
 */
class KeinLoeschpfadTest {

    private static final Path MAIN = Path.of("src/main");
    private static final Pattern LOESCHPFAD = Pattern.compile("(?is)\\b(update|delete\\s+from|truncate)\\b[^;]{0,80}?"
            + "\\b(zugriff_protokoll|dokument_version|benutzer_verzeichnis)\\b");
    private static final Pattern KOMMENTAR = Pattern.compile("(?s)/\\*.*?\\*/|<!--.*?-->|//[^\\n]*");
    private static final Pattern TRIGGER_ODER_RECHT = Pattern.compile("(?is)\\bon\\s+(table\\s+)?[\\w.\"+ ]*$");

    @Test
    void keinCodeAendertLoeschtOderLeertDieProtokolle() throws IOException {
        List<String> funde = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(MAIN)) {
            for (Path datei : dateien.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".xml")
                            || p.toString().endsWith(".sql")).toList()) {
                funde.addAll(funde(datei.toString(), Files.readString(datei, StandardCharsets.UTF_8)));
            }
        }
        assertThat(funde).isEmpty();
    }

    @Test
    void findetLoeschpfadeAuchMitSchemaAusVariable() {
        assertThat(funde("Probe.java", "String sql = \"delete from \" + schema + \".zugriff_protokoll where id = ?\";"))
                .hasSize(1);
        assertThat(funde("Probe.java", "befehl.execute(\"update \" + s + \".benutzer_verzeichnis set name = ?\");"))
                .hasSize(1);
        assertThat(funde("probe.xml", "<sql>truncate dokument_version;</sql>")).hasSize(1);
        assertThat(funde("probe.xml", "create trigger t before update or delete on zugriff_protokoll for each row"))
                .isEmpty();
        assertThat(funde("Probe.java", "\"revoke update, delete, truncate on \" + schema + \".benutzer_verzeichnis\""))
                .isEmpty();
    }

    private static List<String> funde(String name, String inhalt) {
        String ohneKommentare = KOMMENTAR.matcher(inhalt).replaceAll(" ");
        List<String> funde = new ArrayList<>();
        Matcher treffer = LOESCHPFAD.matcher(ohneKommentare);
        while (treffer.find()) {
            String vorTabelle = ohneKommentare.substring(treffer.start(), treffer.start(2));
            if (!TRIGGER_ODER_RECHT.matcher(vorTabelle).find()) {
                funde.add(name + ": " + treffer.group().replaceAll("\\s+", " "));
            }
        }
        return funde;
    }
}
