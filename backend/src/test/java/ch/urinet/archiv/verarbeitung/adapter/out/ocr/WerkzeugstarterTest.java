package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zeitgrenze und Abbruch der Werkzeug-Prozesse (Req 5.6; Pflichten der Handler, design.md «Verarbeitung») und feste
 * Argumentlisten ohne Shell. Die Shell-Aufrufe ({@code sh -c}) gibt es nur hier, um Nachfahren zu erzeugen.
 */
class WerkzeugstarterTest {

    private static final Path BASIS = Path.of("target/ocr-arbeit/starter").toAbsolutePath();
    private static final String ELTERN_UND_KIND = "echo $$ > eltern.pid; sleep 60 & echo $! > kind.pid; wait";

    private Path lauf;

    @BeforeEach
    void anlegen() throws Exception {
        lauf = Files.createTempDirectory(Files.createDirectories(BASIS), "lauf-");
    }

    @AfterEach
    void aufraeumen() throws Exception {
        try (var dateien = Files.walk(lauf)) {
            dateien.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Test
    void zeitueberschreitungBeendetProzessUndNachfahren() throws Exception {
        Werkzeugstarter starter = direkt();
        Befehl befehl = Befehl.von("sh", "-c", ELTERN_UND_KIND);

        Instant start = Instant.now();
        assertThatThrownBy(() -> starter.ausfuehren(befehl, lauf, Instant.now().plusMillis(1500)))
                .isInstanceOf(OcrFehlgeschlagen.class)
                .satisfies(e -> assertThat(((OcrFehlgeschlagen) e).art())
                        .isEqualTo(OcrFehlgeschlagen.Art.ZEITUEBERSCHREITUNG));

        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(15));
        assertBeendet(pid("eltern.pid"));
        assertBeendet(pid("kind.pid"));
    }

    @Test
    void unterbrechungBeendetProzessUndWirftInterruptedException() throws Exception {
        Werkzeugstarter starter = direkt();
        Befehl befehl = Befehl.von("sh", "-c", ELTERN_UND_KIND);
        var ergebnis = new CompletableFuture<Throwable>();
        Thread faden = Thread.ofPlatform().start(() -> {
            try {
                starter.ausfuehren(befehl, lauf, Instant.now().plusSeconds(60));
                ergebnis.complete(null);
            } catch (Throwable t) {
                ergebnis.complete(t);
            }
        });
        warteAufDatei("kind.pid");

        faden.interrupt();

        assertThat(ergebnis.get(15, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        assertBeendet(pid("eltern.pid"));
        assertBeendet(pid("kind.pid"));
    }

    @Test
    void abgelaufeneFristStartetKeinWerkzeug() {
        assertThatThrownBy(() -> direkt().ausfuehren(Befehl.von("sh", "-c", "touch gestartet"), lauf,
                Instant.now().minusSeconds(1)))
                .isInstanceOf(OcrFehlgeschlagen.class);
        assertThat(lauf.resolve("gestartet")).doesNotExist();
    }

    /** Was in einer Shell Befehle auslösen würde, kommt als ein einziges, unverändertes Argument an. */
    @Test
    void argumenteGehenUnveraendertOhneShellAnDasProgramm() throws Exception {
        String boese = "x; touch injiziert $(touch injiziert2) `touch injiziert3` | touch injiziert4 && touch '\"";

        Werkzeugstarter.Lauf ergebnis = direkt().ausfuehren(Befehl.von("printf", "%s", boese), lauf,
                Instant.now().plusSeconds(10));

        assertThat(ergebnis.erfolgreich()).isTrue();
        assertThat(ergebnis.ausgabe()).isEqualTo(boese);
        try (var dateien = Files.list(lauf)) {
            assertThat(dateien.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("injiziert"));
        }
    }

    @Test
    void programmDarfKeineOptionSein() {
        assertThatThrownBy(() -> Befehl.von("--help")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ausgabeUndExitCodeEinesFehlschlags() throws Exception {
        Werkzeugstarter.Lauf ergebnis = direkt().ausfuehren(Befehl.von("sh", "-c", "echo kaputt; exit 3"), lauf,
                Instant.now().plusSeconds(10));

        assertThat(ergebnis.exitCode()).isEqualTo(3);
        assertThat(ergebnis.ausgabe()).contains("kaputt");
    }

    @Test
    void imContainerModusEntferntDieZeitueberschreitungDenContainer() throws Exception {
        String image = WerkzeugImage.sicherstellen();
        var einstellungen = new OcrEinstellungen(Duration.ofMinutes(1), 1, BASIS, Optional.of(image), "docker",
                "/usr/bin:/bin");
        var starter = new Werkzeugstarter(einstellungen, Clock.systemUTC());

        assertThatThrownBy(() -> starter.ausfuehren(Befehl.von("sleep", "120"), lauf, Instant.now().plusSeconds(5)))
                .isInstanceOf(OcrFehlgeschlagen.class);

        assertThat(WerkzeugImage.dockerAusgabe("ps", "-a", "-q", "--filter", "name=archiv-ocr-",
                "--filter", "ancestor=" + image)).isEmpty();
    }

    @Test
    void containerModusOhneNetzOhneRechteUndNurMitDemLaufverzeichnis() {
        var einstellungen = new OcrEinstellungen(Duration.ofMinutes(1), 1, BASIS, Optional.of("werkzeuge:test"),
                "docker", "/usr/bin:/bin");

        List<String> zeile = new Werkzeugstarter(einstellungen, Clock.systemUTC())
                .befehlszeile(Befehl.von("pdftotext", "a"), lauf, "archiv-ocr-x");

        assertThat(String.join(" ", zeile)).contains("--network none", "--cap-drop ALL", "--read-only",
                "--security-opt no-new-privileges", "-v " + lauf + ":" + lauf, "--pull never");
        assertThat(zeile).endsWith("werkzeuge:test", "pdftotext", "a");
    }

    private Werkzeugstarter direkt() {
        var einstellungen = new OcrEinstellungen(Duration.ofMinutes(1), 1, BASIS, Optional.empty(), "docker",
                "/usr/bin:/bin");
        return new Werkzeugstarter(einstellungen, Clock.systemUTC());
    }

    private long pid(String datei) throws Exception {
        warteAufDatei(datei);
        return Long.parseLong(Files.readString(lauf.resolve(datei)).trim());
    }

    private void warteAufDatei(String datei) throws InterruptedException {
        Instant ende = Instant.now().plusSeconds(10);
        while (!Files.exists(lauf.resolve(datei)) && Instant.now().isBefore(ende)) {
            Thread.sleep(50);
        }
        assertThat(lauf.resolve(datei)).exists();
    }

    /**
     * Beendet und abgeholt: kein Prozess mehr, auch kein Zombie (ProcessHandle sieht Zombies als lebend). Verwaiste
     * Nachfahren holt hier der Init des Hosts ab, im Image tini (WerkzeugImageInitTest).
     */
    private static void assertBeendet(long pid) throws InterruptedException {
        Instant ende = Instant.now().plusSeconds(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && Instant.now().isBefore(ende)) {
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).as("Prozess %d", pid)
                .isNotEqualTo(Optional.of(true));
    }
}
