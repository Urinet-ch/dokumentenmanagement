package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Startet ein Werkzeug per {@link ProcessBuilder} mit fester Argumentliste, ohne Shell (design.md «Verarbeitung»).
 * Jeder Aufruf hat eine Frist (Rest des Zeitbudgets): Läuft sie ab oder wird der Thread unterbrochen, beendet der
 * Starter den Prozess und seine Nachfahren mit {@code destroyForcibly} und wartet auf das Ende, damit kein Zombie
 * bleibt (Pflicht der Handler aus PR #41). Im Container-Modus (nur Tests und Profil {@code lokal}) entfernt er zusätzlich
 * den Container mit {@code docker rm -f}.
 * <p>
 * Die Ausgabe der Werkzeuge landet in einer Datei im Laufverzeichnis, nie im Log (Req 16.7); gelesen wird nur das Ende
 * zur Einordnung des Fehlers.
 */
class Werkzeugstarter {

    private static final Logger LOG = Logger.getLogger(Werkzeugstarter.class);
    private static final Duration BEENDEN_FRIST = Duration.ofSeconds(10);
    private static final int MAX_AUSGABE = 16 * 1024;

    /** Exit-Code und Ende der Ausgabe (nur zur Einordnung, nie loggen). */
    record Lauf(int exitCode, String ausgabe) {

        boolean erfolgreich() {
            return exitCode == 0;
        }
    }

    private final OcrEinstellungen einstellungen;
    private final Clock uhr;

    Werkzeugstarter(OcrEinstellungen einstellungen, Clock uhr) {
        this.einstellungen = einstellungen;
        this.uhr = uhr;
    }

    Lauf ausfuehren(Befehl befehl, Path laufverzeichnis, Instant frist) throws InterruptedException {
        Duration rest = Duration.between(uhr.instant(), frist);
        if (rest.isNegative() || rest.isZero()) {
            throw zeitueberschreitung(befehl);
        }
        String container = "archiv-ocr-" + UUID.randomUUID();
        Path ausgabe;
        Process prozess;
        try {
            ausgabe = Files.createTempFile(laufverzeichnis, "ausgabe-", ".log");
            ProcessBuilder pb = new ProcessBuilder(befehlszeile(befehl, laufverzeichnis, container))
                    .directory(laufverzeichnis.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ausgabe.toFile());
            if (einstellungen.containerImage().isEmpty()) {
                umgebung(pb.environment(), laufverzeichnis);
            }
            prozess = pb.start();
            prozess.getOutputStream().close();
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.WERKZEUG, befehl.programm() + " nicht startbar", e);
        }
        boolean fertig;
        try {
            fertig = prozess.waitFor(rest.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            beenden(prozess, container);
            throw e;
        }
        if (!fertig) {
            beenden(prozess, container);
            throw zeitueberschreitung(befehl);
        }
        return new Lauf(prozess.exitValue(), ende(ausgabe));
    }

    /** Vollständige Befehlszeile; im Container-Modus mit {@code docker run} ohne Netz und ohne Rechte. */
    List<String> befehlszeile(Befehl befehl, Path laufverzeichnis, String container) {
        List<String> zeile = new ArrayList<>();
        einstellungen.containerImage().ifPresent(image -> {
            String verzeichnis = laufverzeichnis.toString();
            zeile.addAll(List.of(einstellungen.docker(), "run", "--rm", "--pull", "never", "--name", container,
                    "--network", "none", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only",
                    "--tmpfs", "/tmp", "--user", besitzer(laufverzeichnis),
                    "-e", "HOME=/tmp", "-e", "TMPDIR=" + verzeichnis, "-e", "LANG=C.UTF-8", "-e", "OMP_THREAD_LIMIT=1",
                    "-v", verzeichnis + ":" + verzeichnis, "-w", verzeichnis, image));
        });
        zeile.add(befehl.programm());
        zeile.addAll(befehl.argumente());
        return zeile;
    }

    /** Direkter Aufruf: nur die nötigen Variablen, keine geerbten (Secrets liegen ohnehin nie in Variablen, Regel 7). */
    private void umgebung(Map<String, String> umgebung, Path laufverzeichnis) {
        umgebung.clear();
        umgebung.put("PATH", einstellungen.pfad());
        umgebung.put("HOME", laufverzeichnis.toString());
        umgebung.put("TMPDIR", laufverzeichnis.toString());
        umgebung.put("LANG", "C.UTF-8");
        umgebung.put("OMP_THREAD_LIMIT", "1");
    }

    private static String besitzer(Path verzeichnis) {
        try {
            return Files.getAttribute(verzeichnis, "unix:uid") + ":" + Files.getAttribute(verzeichnis, "unix:gid");
        } catch (IOException | UnsupportedOperationException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Besitzer des Laufverzeichnisses unbekannt",
                    e);
        }
    }

    /**
     * Beendet den Prozess und die Nachfahren, die er gestartet hat (OCRmyPDF startet Tesseract und Ghostscript), und
     * wartet, bis er beendet ist. Die Nachfahren werden vor dem Prozess erfasst, weil sie danach nicht mehr an ihm
     * hängen.
     */
    private void beenden(Process prozess, String container) {
        List<ProcessHandle> nachfahren = prozess.descendants().toList();
        prozess.destroyForcibly();
        nachfahren.forEach(ProcessHandle::destroyForcibly);
        boolean unterbrochen = false;
        try {
            if (!prozess.waitFor(BEENDEN_FRIST.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warnf("Werkzeug-Prozess %d nach destroyForcibly nicht beendet", prozess.pid());
            }
        } catch (InterruptedException e) {
            unterbrochen = true;
        }
        if (einstellungen.containerImage().isPresent()) {
            unterbrochen |= containerEntfernen(container);
        }
        if (unterbrochen) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean containerEntfernen(String container) {
        try {
            Process rm = new ProcessBuilder(einstellungen.docker(), "rm", "-f", container)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!rm.waitFor(BEENDEN_FRIST.toSeconds() * 3, TimeUnit.SECONDS)) {
                rm.destroyForcibly();
                LOG.warnf("Container %s nicht entfernt", container);
            }
            return false;
        } catch (IOException e) {
            LOG.warnf("Container %s nicht entfernt", container);
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    private static String ende(Path ausgabe) {
        try (InputStream ein = Files.newInputStream(ausgabe)) {
            long laenge = Files.size(ausgabe);
            ein.skipNBytes(Math.max(0, laenge - MAX_AUSGABE));
            return new String(ein.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static OcrFehlgeschlagen zeitueberschreitung(Befehl befehl) {
        return new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.ZEITUEBERSCHREITUNG,
                "Zeitgrenze der Verarbeitung überschritten (" + befehl.programm() + ")");
    }
}
