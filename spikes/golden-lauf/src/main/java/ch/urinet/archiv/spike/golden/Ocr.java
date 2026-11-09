package ch.urinet.archiv.spike.golden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Datei → (HEIC per sips → JPEG) → OCRmyPDF im lokalen Docker-Image → pdftotext → pdftoppm -r 300 (design.md
 * «Verarbeitung», «SwissQrLeser»). Der Container läuft ohne Netzwerk; die Daten verlassen den Rechner nicht.
 */
public final class Ocr {

    public record Resultat(String text, List<Path> seiten) {
    }

    private final String image;
    private final String sprachen;
    private final Duration zeitgrenze;

    public Ocr(String image, String sprachen, Duration zeitgrenze) {
        this.image = image;
        this.sprachen = sprachen;
        this.zeitgrenze = zeitgrenze;
    }

    public Resultat verarbeite(Path datei, Path arbeit) throws IOException, InterruptedException {
        Files.createDirectories(arbeit);
        String endung = endung(datei);
        Path eingang;
        if (endung.equals("heic")) {
            eingang = arbeit.resolve("eingang.jpg");
            ausfuehren(List.of("sips", "-s", "format", "jpeg", datei.toString(), "--out", eingang.toString()), arbeit);
        } else {
            eingang = arbeit.resolve("eingang." + endung);
            Files.copy(datei, eingang, StandardCopyOption.REPLACE_EXISTING);
        }
        String skript = String.join(" && ",
                "ocrmypdf -q -l " + sprachen + " --skip-text --rotate-pages --deskew --output-type pdf"
                        + (endung.equals("pdf") ? "" : " --image-dpi 300") + " " + eingang.getFileName() + " ocr.pdf",
                "pdftotext -layout -enc UTF-8 ocr.pdf text.txt",
                "pdftoppm -r 300 -png ocr.pdf seite");
        ausfuehren(List.of("docker", "run", "--rm", "--network", "none", "-v", arbeit.toAbsolutePath() + ":/data",
                "-w", "/data", "--entrypoint", "sh", image, "-c", skript), arbeit);
        List<Path> seiten;
        try (Stream<Path> s = Files.list(arbeit)) {
            seiten = new ArrayList<>(s.filter(p -> p.getFileName().toString().matches("seite-\\d+\\.png")).sorted().toList());
        }
        return new Resultat(Files.readString(arbeit.resolve("text.txt"), StandardCharsets.UTF_8), seiten);
    }

    private void ausfuehren(List<String> befehl, Path arbeit) throws IOException, InterruptedException {
        Path log = arbeit.resolve("werkzeug.log");
        Process p = new ProcessBuilder(befehl).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(zeitgrenze.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException("Zeitgrenze überschritten: " + befehl.getFirst());
        }
        if (p.exitValue() != 0) {
            // Werkzeugausgabe bleibt in der Arbeitskopie, nicht im Log (kann Belegtext enthalten)
            throw new IOException(befehl.getFirst() + " Exit " + p.exitValue());
        }
    }

    private static String endung(Path datei) {
        String n = datei.getFileName().toString();
        return n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
    }
}
