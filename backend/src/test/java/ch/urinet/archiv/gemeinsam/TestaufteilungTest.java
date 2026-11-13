package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vollständigkeit der aufgeteilten Backend-Tests in der CI ({@code .github/scripts/pruefe-testaufteilung.py}, Job
 * «testzahl», Entscheid Fabian 13.11.2026): Jede Testklasse lief in genau einem Job, kein Job ist leer.
 */
class TestaufteilungTest {

    private static final Path SKRIPT = GemeinsamSchritte.REPO.resolve(".github/scripts/pruefe-testaufteilung.py");

    @TempDir
    Path tmp;
    private Path quellen;

    @BeforeEach
    void quellenAnlegen() throws IOException {
        quellen = tmp.resolve("quellen");
        quelle("ch/x/EinsTest", "class EinsTest { @Test void a() {} }");
        quelle("ch/x/ZweiTest", "@Tag(\"start-1\") class ZweiTest { @Test void a() {} }");
        quelle("ch/x/ArchTests", "class ArchTests { @ArchTest static final Object r = null; }");
        quelle("ch/x/TestHilfe", "public final class TestHilfe { }");
        quelle("ch/x/BasisTest", "abstract class BasisTest { @Test void a() {} }");
    }

    @Test
    void jedeKlasseGenauEinmal() throws Exception {
        bericht("a", "ch.x.EinsTest", 3, 0);
        bericht("a", "ch.x.ArchTests", 1, 0);
        bericht("b", "ch.x.ZweiTest", 2, 1);

        Ergebnis e = pruefe("a", "b");

        assertThat(e.code()).as(e.ausgabe()).isZero();
        assertThat(e.ausgabe()).contains("| a | 2 | 4 | 0 |", "| b | 1 | 2 | 1 |", "| Summe | 3 | 6 | 1 |",
                "Alle 3 Testklassen");
    }

    @Test
    void fehlendeKlasseBrichtAb() throws Exception {
        bericht("a", "ch.x.EinsTest", 3, 0);
        bericht("b", "ch.x.ZweiTest", 2, 0);

        Ergebnis e = pruefe("a", "b");

        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("ch.x.ArchTests: lief in 0 Jobs");
    }

    @Test
    void doppelteKlasseBrichtAb() throws Exception {
        bericht("a", "ch.x.EinsTest", 3, 0);
        bericht("a", "ch.x.ArchTests", 1, 0);
        bericht("b", "ch.x.ZweiTest", 2, 0);
        bericht("b", "ch.x.EinsTest", 3, 0);
        bericht("b", "ch.x.Unbekannt", 1, 0);
        bericht("a", "ch.x.Unbekannt", 1, 0);

        Ergebnis e = pruefe("a", "b");

        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("ch.x.EinsTest: lief in 2 Jobs", "ch.x.Unbekannt: lief in mehreren Jobs");
    }

    @Test
    void leererJobBrichtAb() throws Exception {
        bericht("a", "ch.x.EinsTest", 3, 0);
        bericht("a", "ch.x.ArchTests", 1, 0);
        bericht("a", "ch.x.ZweiTest", 2, 0);
        Files.createDirectories(tmp.resolve("c"));

        Ergebnis e = pruefe("a", "c");

        assertThat(e.code()).isEqualTo(1);
        assertThat(e.ausgabe()).contains("Job c: keine Tests");
    }

    private void quelle(String pfad, String inhalt) throws IOException {
        Path datei = quellen.resolve(pfad + ".java");
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
    }

    private void bericht(String job, String klasse, int tests, int uebersprungen) throws IOException {
        Path datei = tmp.resolve(job).resolve("TEST-" + klasse + ".xml");
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"" + klasse
                + "\" tests=\"" + tests + "\" skipped=\"" + uebersprungen + "\" failures=\"0\" errors=\"0\"/>\n");
    }

    private Ergebnis pruefe(String... jobs) throws Exception {
        List<String> befehl = new ArrayList<>(List.of("python3", SKRIPT.toString(), quellen.toString()));
        for (String job : jobs) {
            befehl.add(job + "=" + tmp.resolve(job));
        }
        ProcessBuilder pb = new ProcessBuilder(befehl).redirectErrorStream(true);
        pb.environment().remove("GITHUB_STEP_SUMMARY");
        Process p = pb.start();
        String ausgabe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Ergebnis(p.exitValue(), ausgabe);
    }

    private record Ergebnis(int code, String ausgabe) {
    }
}
