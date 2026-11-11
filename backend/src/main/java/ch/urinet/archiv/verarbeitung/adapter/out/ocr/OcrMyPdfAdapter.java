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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    static final int OCRMYPDF_VERSCHLUESSELT = 8;
    static final int OCRMYPDF_PDFA_GESCHEITERT = 10;

    private static final Pattern VORSCHAU = Pattern.compile("vorschau-(\\d+)\\.png");

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
