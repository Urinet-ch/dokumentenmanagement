package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.verarbeitung.application.OcrPort;
import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;
import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
import ch.urinet.archiv.verarbeitung.domain.OcrQuelle;
import ch.urinet.archiv.verarbeitung.domain.PdfaFehlt;
import ch.urinet.archiv.verarbeitung.domain.Quelltyp;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Adapter des {@link OcrPort} mit OCRmyPDF, Tesseract, img2pdf, libheif und Poppler (design.md «Verarbeitung»; Req
 * 5.1, 5.2, 5.8–5.10). Ein Lauf kopiert die Quellen unter festen Namen ({@code quelle-<n>.<endung>}) in ein eigenes
 * Verzeichnis mit Rechten 700, ruft die Werkzeuge mit fester Argumentliste ohne Shell und löscht das Verzeichnis nach
 * der Verwertung. Kein Name und kein Inhalt aus der Eingabe erscheint in einer Befehlszeile oder im Log.
 */
public class OcrMyPdfAdapter implements OcrPort {

    private static final Logger LOG = Logger.getLogger(OcrMyPdfAdapter.class);

    /** Exit-Codes von OCRmyPDF ({@code ocrmypdf.ExitCode}). */
    static final int OCRMYPDF_EINGABE = 2;
    /** Seite hat schon Text, obwohl {@code pdftotext} keinen fand (z. B. erst ab Seite 51 oder nur Satzzeichen). */
    static final int OCRMYPDF_TEXT_VORHANDEN = 6;
    static final int OCRMYPDF_VERSCHLUESSELT = 8;
    static final int OCRMYPDF_PDFA_GESCHEITERT = 10;

    private static final Pattern VORSCHAU = Pattern.compile("vorschau-(\\d+)\\.png");
    /**
     * Seitenbilder ohne die teure PNG-Kompression (A4 mit 300 dpi: rund 0.15 s statt 6.5 s CPU je Seite; Nachtrag PR
     * #93): graue Seiten (QR) als TIFF mit LZW, farbige Seiten (Bildweg) als unkomprimiertes PPM ({@code pdftoppm} ohne
     * Format, Review PR #93), beide verlustfrei mit denselben Pixeln wie das PNG des Golden-Laufs. Ein farbiges TIFF
     * liest ImageIO nicht als {@code TYPE_3BYTE_BGR}, und das Skalieren in {@code AwtSeitenbild} bräuchte dann ein
     * volles Int-Raster (OOM mit {@code -Xmx112m}); das PPM liest {@code PpmLeser} direkt in diesen Typ.
     */
    private static final Pattern SEITE = Pattern.compile("seite-(\\d+)\\.(?:tif|ppm)");
    /** Auflösung des Golden-Laufs ({@code pdftoppm -r 300}). */
    static final int RENDER_DPI = 300;
    static final int MIN_DPI = 10;

    private final OcrEinstellungen einstellungen;
    private final Werkzeugstarter starter;
    private final Clock uhr;

    public OcrMyPdfAdapter(OcrEinstellungen einstellungen) {
        this(einstellungen, Clock.systemUTC());
    }

    OcrMyPdfAdapter(OcrEinstellungen einstellungen, Clock uhr) {
        this(einstellungen, new Werkzeugstarter(einstellungen, uhr), uhr);
    }

    OcrMyPdfAdapter(OcrEinstellungen einstellungen, Werkzeugstarter starter, Clock uhr) {
        this.einstellungen = einstellungen;
        this.starter = starter;
        this.uhr = uhr;
    }

    @Override
    public <T> T verarbeiten(OcrEingabe eingabe, Verwertung<T> verwertung) throws InterruptedException {
        Instant frist = uhr.instant().plus(einstellungen.zeitgrenze());
        Path lauf = laufverzeichnisAnlegen();
        try {
            OcrErgebnis ergebnis = new Lauf(lauf, frist).ausfuehren(eingabe);
            pruefeUnterbrechung();
            return verwertung.verwerten(ergebnis);
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Ein- oder Ausgabe der Verarbeitung "
                    + "gescheitert", e);
        } finally {
            loeschen(lauf);
        }
    }

    @Override
    public String layoutText(Path pdf, Duration zeitgrenze) throws InterruptedException {
        Instant frist = uhr.instant().plus(zeitgrenze);
        Path lauf = laufverzeichnisAnlegen();
        try {
            return new Lauf(lauf, frist).layoutText(pdf);
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Ein- oder Ausgabe beim Lesen des Texts "
                    + "gescheitert", e);
        } finally {
            loeschen(lauf);
        }
    }

    @Override
    public <T> T seitenbilder(Path pdf, Render render, Seitenverwertung<T> verwertung) throws InterruptedException {
        Instant frist = uhr.instant().plus(render.zeitgrenze());
        Path lauf = laufverzeichnisAnlegen();
        try {
            List<Path> seiten = new Lauf(lauf, frist).seitenbilder(pdf, render);
            pruefeUnterbrechung();
            return verwertung.verwerten(seiten);
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Ein- oder Ausgabe beim Rendern "
                    + "gescheitert", e);
        } finally {
            loeschen(lauf);
        }
    }

    /**
     * Auflösung einer Seite: 300 dpi wie im Golden-Lauf, solange die Seite dann höchstens {@code pixelMax} Pixel hat
     * (A4 mit 300 dpi bleibt so unverändert), sonst die grösste ganze Zahl dpi darunter. Leer, wenn das Format nicht
     * lesbar ist oder auch {@value #MIN_DPI} dpi die Grenze sprengen.
     */
    static OptionalInt dpi(double breitePt, double hoehePt, long pixelMax) {
        if (!(breitePt > 0 && hoehePt > 0) || Double.isInfinite(breitePt) || Double.isInfinite(hoehePt)) {
            return OptionalInt.empty();
        }
        for (int dpi = RENDER_DPI; dpi >= MIN_DPI; dpi = Math.min(dpi - 1,
                (int) Math.floor(72 * Math.sqrt(pixelMax / (breitePt * hoehePt))))) {
            if (Math.ceil(breitePt * dpi / 72) * Math.ceil(hoehePt * dpi / 72) <= pixelMax) {
                return OptionalInt.of(dpi);
            }
        }
        return OptionalInt.empty();
    }

    /** Seitenzahl und Format je Seite aus der Ausgabe von {@code pdfinfo -f … -l …}. */
    record Seiteninfo(int seiten, Map<Integer, double[]> formate) {

        private static final Pattern SEITEN = Pattern.compile("(?m)^Pages:\\s+(\\d+)\\s*$");
        private static final Pattern FORMAT = Pattern.compile(
                "(?m)^Page\\s+(\\d+)\\s+size:\\s+([0-9.]+)\\s+x\\s+([0-9.]+)\\s+pts");

        static Seiteninfo lesen(String ausgabe) {
            Matcher m = SEITEN.matcher(ausgabe);
            int seiten = m.find() ? Integer.parseInt(m.group(1)) : 0;
            Map<Integer, double[]> formate = new HashMap<>();
            Matcher f = FORMAT.matcher(ausgabe);
            while (f.find()) {
                try {
                    formate.put(Integer.parseInt(f.group(1)),
                            new double[]{Double.parseDouble(f.group(2)), Double.parseDouble(f.group(3))});
                } catch (NumberFormatException e) {
                    // Format nicht lesbar: Seite fehlt
                }
            }
            return new Seiteninfo(seiten, formate);
        }
    }

    private Path laufverzeichnisAnlegen() {
        try {
            Files.createDirectories(einstellungen.arbeitsverzeichnis());
            return Files.createTempDirectory(einstellungen.arbeitsverzeichnis(), "lauf-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException e) {
            throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.EIN_AUSGABE, "Arbeitsverzeichnis nicht anlegbar", e);
        }
    }

    /** Ein Lauf in einem Verzeichnis; alle Pfade absolut, alle Namen fest. */
    private final class Lauf {

        private final Path verzeichnis;
        private final Instant frist;

        Lauf(Path verzeichnis, Instant frist) {
            this.verzeichnis = verzeichnis;
            this.frist = frist;
        }

        OcrErgebnis ausfuehren(OcrEingabe eingabe) throws IOException, InterruptedException {
            List<Path> quellen = kopieren(eingabe.quellen());
            Path eingang = eingabe.istPdf() ? quellen.getFirst() : bilderZuPdf(eingabe.quellen(), quellen);
            boolean textebene = eingabe.istPdf() && hatTextebene(eingang);
            Path pdfa = datei("pdfa.pdf");
            Werkzeugstarter.Lauf ocr = ocrmypdf(eingang, pdfa, textebene, false);
            boolean signaturUngueltig = false;
            if (ocr.exitCode() == OCRMYPDF_EINGABE && signiert(ocr)) {
                Files.deleteIfExists(pdfa);
                ocr = ocrmypdf(eingang, pdfa, textebene, true);
                signaturUngueltig = true;
            }
            if (ocr.exitCode() == OCRMYPDF_TEXT_VORHANDEN && !textebene) {
                Files.deleteIfExists(pdfa);
                textebene = true;
                ocr = ocrmypdf(eingang, pdfa, true, signaturUngueltig);
            }
            Optional<PdfaFehlt> fehlt = pdfaFehlt(ocr);
            Path fassung = fehlt.isEmpty() ? pdfa : eingang;
            String volltext = text(fassung, fehlt.isEmpty());
            List<Path> vorschau = vorschau(fassung, fehlt.isEmpty());
            return new OcrErgebnis(fehlt.isEmpty() ? Optional.of(pdfa) : Optional.empty(), fehlt, volltext, vorschau,
                    !textebene && fehlt.isEmpty(), signaturUngueltig && fehlt.isEmpty());
        }

        private List<Path> kopieren(List<OcrQuelle> quellen) throws IOException {
            List<Path> kopien = new ArrayList<>();
            for (int i = 0; i < quellen.size(); i++) {
                OcrQuelle quelle = quellen.get(i);
                Path ziel = datei("quelle-" + (i + 1) + "." + quelle.typ().endung());
                try (InputStream ein = quelle.inhalt().oeffnen()) {
                    Files.copy(ein, ziel);
                }
                kopien.add(ziel);
            }
            return kopien;
        }

        /** HEIC → JPEG ({@code heif-convert}), danach alle Bilder in Reihenfolge → ein PDF ({@code img2pdf}). */
        private Path bilderZuPdf(List<OcrQuelle> quellen, List<Path> kopien) throws IOException, InterruptedException {
            List<String> argumente = new ArrayList<>(List.of("-o", datei("bilder.pdf").toString()));
            for (int i = 0; i < kopien.size(); i++) {
                if (quellen.get(i).typ() == Quelltyp.HEIC) {
                    Path jpeg = datei("heic-" + (i + 1) + ".jpg");
                    erfolgreich(Befehl.von("heif-convert", "--quiet", "-q", "92", kopien.get(i).toString(),
                            jpeg.toString()));
                    argumente.addAll(heicBilder(jpeg, i + 1));
                } else {
                    argumente.add(kopien.get(i).toString());
                }
            }
            erfolgreich(new Befehl("img2pdf", argumente));
            return datei("bilder.pdf");
        }

        /**
         * {@code heif-convert} schreibt bei mehreren Bildern in einer Datei {@code heic-<n>-1.jpg}, {@code -2} usw.;
         * Zusatzbilder (Tiefe, Alpha) nur mit {@code --with-aux}, darum nicht.
         */
        private List<String> heicBilder(Path jpeg, int nummer) throws IOException {
            if (Files.exists(jpeg)) {
                return List.of(jpeg.toString());
            }
            Pattern muster = Pattern.compile("heic-" + nummer + "-(\\d+)\\.jpg");
            List<String> bilder = nummeriert(muster);
            if (bilder.isEmpty()) {
                throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.WERKZEUG, "heif-convert lieferte kein Bild");
            }
            return bilder;
        }

        /** Textebene vorhanden, wenn {@code pdftotext} Buchstaben oder Ziffern liefert (Req 5.2). */
        private boolean hatTextebene(Path pdf) throws IOException, InterruptedException {
            Path text = datei("textebene.txt");
            Werkzeugstarter.Lauf lauf = starter.ausfuehren(Befehl.von("pdftotext", "-q", "-enc", "UTF-8", "-l", "50",
                    pdf.toString(), text.toString()), verzeichnis, frist);
            if (!lauf.erfolgreich() || !Files.exists(text)) {
                return false;
            }
            boolean vorhanden = Files.readString(text, StandardCharsets.UTF_8).codePoints()
                    .anyMatch(Character::isLetterOrDigit);
            Files.delete(text);
            return vorhanden;
        }

        private Werkzeugstarter.Lauf ocrmypdf(Path eingang, Path ausgang, boolean textebene, boolean signaturFreigeben)
                throws InterruptedException {
            List<String> argumente = new ArrayList<>(List.of("--output-type", "pdfa-2", "--rotate-pages", "--deskew",
                    "-l", "deu+eng", "--jobs", Integer.toString(einstellungen.jobs())));
            if (textebene) {
                argumente.add("--skip-text");
            }
            if (signaturFreigeben) {
                argumente.add("--invalidate-digital-signatures");
            }
            argumente.add(eingang.toString());
            argumente.add(ausgang.toString());
            return starter.ausfuehren(new Befehl("ocrmypdf", argumente), verzeichnis, frist);
        }

        private boolean signiert(Werkzeugstarter.Lauf lauf) {
            return lauf.ausgabe().toLowerCase(Locale.ROOT).contains("digital signature");
        }

        /** Req 5.9: verschlüsselt oder keine gültige PDF/A → {@code pdfa_fehlt}; jeder andere Fehler wiederholt sich. */
        private Optional<PdfaFehlt> pdfaFehlt(Werkzeugstarter.Lauf lauf) throws IOException {
            return switch (lauf.exitCode()) {
                case 0 -> {
                    if (!Files.isRegularFile(datei("pdfa.pdf"))) {
                        throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.WERKZEUG, "ocrmypdf lieferte keine Datei");
                    }
                    yield Optional.empty();
                }
                case OCRMYPDF_VERSCHLUESSELT -> Optional.of(new PdfaFehlt(PdfaFehlt.Grund.VERSCHLUESSELT));
                case OCRMYPDF_PDFA_GESCHEITERT -> {
                    Files.deleteIfExists(datei("pdfa.pdf"));
                    yield Optional.of(new PdfaFehlt(PdfaFehlt.Grund.KONVERTIERUNG));
                }
                default -> throw werkzeugFehler("ocrmypdf", lauf);
            };
        }

        /** Volltext mit {@code \f} als Seitentrenner; ohne PDF/A nur soweit lesbar (Req 5.9). */
        private String text(Path pdf, boolean pflicht) throws IOException, InterruptedException {
            Path text = datei("volltext.txt");
            Werkzeugstarter.Lauf lauf = starter.ausfuehren(Befehl.von("pdftotext", "-q", "-enc", "UTF-8",
                    pdf.toString(), text.toString()), verzeichnis, frist);
            if (!lauf.erfolgreich() || !Files.exists(text)) {
                if (pflicht) {
                    throw werkzeugFehler("pdftotext", lauf);
                }
                return "";
            }
            return Files.readString(text, StandardCharsets.UTF_8);
        }

        /** PNG je Seite mit 72 dpi; ohne PDF/A nur soweit lesbar (Req 5.9). */
        private List<Path> vorschau(Path pdf, boolean pflicht) throws IOException, InterruptedException {
            Werkzeugstarter.Lauf lauf = starter.ausfuehren(Befehl.von("pdftoppm", "-q", "-r", "72", "-png",
                    pdf.toString(), datei("vorschau").toString()), verzeichnis, frist);
            if (!lauf.erfolgreich()) {
                if (pflicht) {
                    throw werkzeugFehler("pdftoppm", lauf);
                }
                return List.of();
            }
            return nummeriert(VORSCHAU).stream().map(Path::of).toList();
        }

        /** Wie im Golden-Lauf ({@code Ocr}): {@code pdftotext -layout -enc UTF-8}; das PDF zuerst ins Laufverzeichnis. */
        String layoutText(Path pdf) throws IOException, InterruptedException {
            Path kopie = datei("modell.pdf");
            Files.copy(pdf, kopie);
            Path text = datei("layout.txt");
            erfolgreich(Befehl.von("pdftotext", "-q", "-layout", "-enc", "UTF-8", kopie.toString(), text.toString()));
            if (!Files.isRegularFile(text)) {
                throw new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.WERKZEUG, "pdftotext lieferte keine Datei");
            }
            return Files.readString(text, StandardCharsets.UTF_8);
        }

        /**
         * Seiten Seite für Seite mit der Auflösung aus {@link #dpi}, farbig als PPM (Pixel wie {@code pdftoppm -r 300
         * -png} im Golden-Lauf) oder mit {@code -gray} als TIFF mit LZW (siehe {@link #SEITE}); das PDF wird zuerst ins
         * Laufverzeichnis kopiert.
         */
        List<Path> seitenbilder(Path pdf, Render render) throws IOException, InterruptedException {
            Path kopie = datei("seiten.pdf");
            Files.copy(pdf, kopie);
            Seiteninfo info = seiteninfo(kopie, 1, render.maxSeiten());
            List<Integer> nummern = new ArrayList<>();
            for (int i = 1; i <= Math.min(info.seiten(), render.maxSeiten()); i++) {
                nummern.add(i);
            }
            Map<Integer, double[]> formate = new HashMap<>(info.formate());
            if (info.seiten() > render.maxSeiten()) {
                nummern.add(info.seiten());
                formate.putAll(seiteninfo(kopie, info.seiten(), info.seiten()).formate());
            }
            for (int nummer : nummern) {
                double[] format = formate.get(nummer);
                OptionalInt dpi = format == null ? OptionalInt.empty() : dpi(format[0], format[1], render.pixelMax());
                if (dpi.isEmpty()) {
                    LOG.infof("Seite %d nicht gerendert: Format ausserhalb der Grenze", nummer);
                    continue;
                }
                List<String> argumente = new ArrayList<>(List.of("-q", "-r", Integer.toString(dpi.getAsInt()),
                        "-f", Integer.toString(nummer), "-l", Integer.toString(nummer)));
                if (!render.farbig()) {
                    argumente.addAll(List.of("-gray", "-tiff", "-tiffcompression", "lzw"));
                }
                argumente.addAll(List.of(kopie.toString(), datei("seite").toString()));
                erfolgreich(new Befehl("pdftoppm", argumente));
            }
            return nummeriert(SEITE).stream().map(Path::of).toList();
        }

        private Seiteninfo seiteninfo(Path pdf, int von, int bis) throws InterruptedException {
            Werkzeugstarter.Lauf lauf = starter.ausfuehren(Befehl.von("pdfinfo", "-f", Integer.toString(von), "-l",
                    Integer.toString(bis), pdf.toString()), verzeichnis, frist);
            if (!lauf.erfolgreich()) {
                throw werkzeugFehler("pdfinfo", lauf);
            }
            return Seiteninfo.lesen(lauf.ausgabe());
        }

        /** Dateien im Laufverzeichnis nach der Nummer der ersten Gruppe sortiert (pdftoppm füllt mit Nullen auf). */
        private List<String> nummeriert(Pattern muster) throws IOException {
            try (Stream<Path> dateien = Files.list(verzeichnis)) {
                return dateien.map(p -> muster.matcher(p.getFileName().toString()))
                        .filter(Matcher::matches)
                        .sorted(Comparator.comparingInt(m -> Integer.parseInt(m.group(1))))
                        .map(m -> datei(m.group()).toString())
                        .toList();
            }
        }

        private void erfolgreich(Befehl befehl) throws InterruptedException {
            Werkzeugstarter.Lauf lauf = starter.ausfuehren(befehl, verzeichnis, frist);
            if (!lauf.erfolgreich()) {
                throw werkzeugFehler(befehl.programm(), lauf);
            }
        }

        private Path datei(String name) {
            return verzeichnis.resolve(name);
        }
    }

    private static OcrFehlgeschlagen werkzeugFehler(String programm, Werkzeugstarter.Lauf lauf) {
        return new OcrFehlgeschlagen(OcrFehlgeschlagen.Art.WERKZEUG, programm + " endete mit Exit-Code "
                + lauf.exitCode());
    }

    private static void pruefeUnterbrechung() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Verarbeitung unterbrochen");
        }
    }

    /** Löscht das Laufverzeichnis; scheitert das, bleibt es liegen, und das Log nennt nur den Namen. */
    private static void loeschen(Path lauf) {
        try {
            Files.walkFileTree(lauf, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) throws IOException {
                    Files.delete(datei);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path verzeichnis, IOException fehler) throws IOException {
                    Files.delete(verzeichnis);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf("Laufverzeichnis %s nicht gelöscht (%s)", lauf.getFileName(), e.getClass().getSimpleName());
        }
    }
}
