package ch.urinet.archiv.spike.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Datei → (HEIC per sips → JPEG) → OCRmyPDF im lokalen Docker-Image → pdftotext → pdftoppm -r 300 (design.md
 * «Verarbeitung», «SwissQrLeser»). Der Container läuft ohne Netzwerk; die Daten verlassen den Rechner nicht.
 * Ein Arbeitsordner gilt erst mit der Datei {@value #FERTIG} als vollständig; schlägt ein Schritt fehl, wird er gelöscht.
 */
public final class Ocr {

    public record Resultat(String text, List<Path> seiten) {
    }

    static final String FERTIG = "fertig";

    private final String image;
    private final String sprachen;
    private final Duration zeitgrenze;
    private final String docker;

    public Ocr(String image, String sprachen, Duration zeitgrenze) {
        this(image, sprachen, zeitgrenze, "docker");
    }

    Ocr(String image, String sprachen, Duration zeitgrenze, String docker) {
        this.image = image;
        this.sprachen = sprachen;
        this.zeitgrenze = zeitgrenze;
        this.docker = docker;
    }

    /** Vollständiges Ergebnis aus einem früheren Lauf, sonst leer. */
    public static Optional<Resultat> ausCache(Path arbeit) throws IOException {
        if (!Files.isRegularFile(arbeit.resolve(FERTIG))) {
            return Optional.empty();
        }
        return Optional.of(new Resultat(Files.readString(arbeit.resolve("text.txt"), StandardCharsets.UTF_8), seiten(arbeit)));
    }

    public Resultat verarbeite(Path datei, Path arbeit) throws IOException, InterruptedException {
        try {
            loesche(arbeit);
            Files.createDirectories(arbeit);
            String endung = endung(datei);
            Path eingang;
            if (endung.equals("heic")) {
                eingang = arbeit.resolve("eingang.jpg");
                ausfuehren(List.of("sips", "-s", "format", "jpeg", datei.toString(), "--out", eingang.toString()), arbeit, null);
            } else {
                eingang = arbeit.resolve("eingang." + endung);
                Files.copy(datei, eingang, StandardCopyOption.REPLACE_EXISTING);
            }
            String skript = String.join(" && ",
                    "ocrmypdf -q -l " + sprachen + " --skip-text --rotate-pages --deskew --output-type pdf"
                            + (endung.equals("pdf") ? "" : " --image-dpi 300") + " " + eingang.getFileName() + " ocr.pdf",
                    "pdftotext -layout -enc UTF-8 ocr.pdf text.txt",
                    "pdftoppm -r 300 -png ocr.pdf seite");
            String container = "golden-ocr-" + UUID.randomUUID();
            ausfuehren(List.of(docker, "run", "--rm", "--name", container, "--network", "none",
                    "-v", arbeit.toAbsolutePath() + ":/data", "-w", "/data", "--entrypoint", "sh", image, "-c", skript),
                    arbeit, container);
            List<Path> seiten = seiten(arbeit);
            if (seiten.isEmpty() || !Files.isRegularFile(arbeit.resolve("text.txt"))) {
                throw new OcrFehler("OCR ohne Text oder Seitenbilder");
            }
            Files.writeString(arbeit.resolve(FERTIG), "");
            return new Resultat(Files.readString(arbeit.resolve("text.txt"), StandardCharsets.UTF_8), seiten);
        } catch (IOException | InterruptedException | RuntimeException e) {
            try {
                loesche(arbeit);
            } catch (IOException | RuntimeException loeschfehler) {
                // Rest ohne «fertig» wird beim nächsten Lauf ersetzt; der Beleg zählt als Fehlschlag
                e.addSuppressed(loeschfehler);
            }
            throw e;
        }
    }

    /** Eigene Meldungen (Exit-Code, Zeitgrenze) sind ohne Pfade; sonst nur der Klassenname (Pfade enthalten Dateinamen). */
    static String grund(Exception e) {
        return e instanceof OcrFehler ? e.getMessage() : KiFehler.grund(e);
    }

    /** Fehler mit selbst formulierter Meldung ohne Pfade oder Belegtext. */
    static final class OcrFehler extends IOException {
        OcrFehler(String meldung) {
            super(meldung);
        }
    }

    private void ausfuehren(List<String> befehl, Path arbeit, String container) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(befehl).redirectErrorStream(true)
                .redirectOutput(arbeit.resolve("werkzeug.log").toFile()).start();
        if (!p.waitFor(zeitgrenze.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            // destroyForcibly beendet nur den docker-Client, nicht den Container
            if (container != null) {
                stoppe(container);
            }
            throw new OcrFehler("Zeitgrenze überschritten: " + Path.of(befehl.getFirst()).getFileName());
        }
        if (p.exitValue() != 0) {
            // nur der Exit-Code, nicht die Werkzeugausgabe (kann Belegtext enthalten)
            throw new OcrFehler(Path.of(befehl.getFirst()).getFileName() + " Exit " + p.exitValue());
        }
    }

    private void stoppe(String container) throws InterruptedException {
        try {
            Process kill = new ProcessBuilder(docker, "kill", container).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!kill.waitFor(30, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
            }
        } catch (IOException e) {
            // Container evtl. schon beendet; der Beleg zählt ohnehin als Fehlschlag
        }
    }

    private static List<Path> seiten(Path arbeit) throws IOException {
        try (Stream<Path> s = Files.list(arbeit)) {
            return new ArrayList<>(s.filter(p -> p.getFileName().toString().matches("seite-\\d+\\.png")).sorted().toList());
        }
    }

    static void loesche(Path ordner) throws IOException {
        if (!Files.exists(ordner)) {
            return;
        }
        try (Stream<Path> s = Files.walk(ordner)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String endung(Path datei) {
        String n = datei.getFileName().toString();
        return n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
    }
}
