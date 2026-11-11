package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;
import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;
import ch.urinet.archiv.verarbeitung.domain.OcrQuelle;
import ch.urinet.archiv.verarbeitung.domain.PdfaFehlt;
import ch.urinet.archiv.verarbeitung.domain.Quelltyp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien.seite;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR-Adapter gegen die echten Werkzeuge im Image der Stufe {@code werkzeuge} (Container-Modus); PDF/A-Prüfung mit
 * veraPDF. Nachweis auf Adapterebene für Req 5.1, 5.2, 5.8, 5.9 und 5.10 (Teil a von Aufgabe 2.6; Status und Handler
 * folgen in Teil b).
 */
class OcrMyPdfAdapterTest {

    private static final Path BASIS = Path.of("target/ocr-arbeit/adapter").toAbsolutePath();
    private static String image;

    private final List<Befehl> befehle = Collections.synchronizedList(new ArrayList<>());
    private OcrMyPdfAdapter adapter;

    @BeforeAll
    static void imageBauen() throws Exception {
        image = WerkzeugImage.sicherstellen();
    }

    @BeforeEach
    void anlegen() {
        var einstellungen = new OcrEinstellungen(Duration.ofMinutes(5), 1, BASIS, Optional.of(image), "docker",
                "/usr/bin:/bin");
        var starter = new Werkzeugstarter(einstellungen, Clock.systemUTC()) {
            @Override
            Lauf ausfuehren(Befehl befehl, Path laufverzeichnis, Instant frist) throws InterruptedException {
                befehle.add(befehl);
                return super.ausfuehren(befehl, laufverzeichnis, frist);
            }
        };
        adapter = new OcrMyPdfAdapter(einstellungen, starter, Clock.systemUTC());
    }

    @Test
    void schiefesFotoWirdZuDurchsuchbaremPdfa() throws Exception {
        byte[] foto = OcrTestdateien.jpeg(seite(3, "Testbeleg Fotografie", "Rechnung Nummer 4711", "Betrag CHF 99.00"));

        Pruefung p = verarbeiten(OcrEingabe.von(quelle(Quelltyp.JPEG, foto)));

        assertThat(p.pdfa).isTrue();
        assertThat(p.ergebnis.ocrAusgefuehrt()).isTrue();
        assertThat(p.ergebnis.volltext()).contains("Rechnung").contains("4711");
        assertThat(p.ergebnis.vorschaubilder()).hasSize(1);
        assertThat(befehle).extracting(Befehl::programm).containsExactly("img2pdf", "ocrmypdf", "pdftotext",
                "pdftoppm");
        assertThat(befehle.get(1).argumente()).contains("--rotate-pages", "--deskew", "deu+eng", "pdfa-2")
                .doesNotContain("--skip-text");
    }

    @Test
    void heicWirdUeberJpegZuPdfa() throws Exception {
        Pruefung p = verarbeiten(OcrEingabe.von(quelle(Quelltyp.HEIC, OcrTestdateien.heic())));

        assertThat(p.pdfa).isTrue();
        assertThat(p.ergebnis.volltext()).contains("Quittung");
        assertThat(befehle).extracting(Befehl::programm).startsWith("heif-convert", "img2pdf", "ocrmypdf");
    }

    @Test
    void mehrseitigesPdfBehaeltSeitentrennerUndVorschauJeSeite() throws Exception {
        byte[] pdf = OcrTestdateien.bildPdf(List.of(seite(0, "Erste Seite Alpha"), seite(0, "Zweite Seite Beta"),
                seite(0, "Dritte Seite Gamma")));

        Pruefung p = verarbeiten(OcrEingabe.von(quelle(Quelltyp.PDF, pdf)));

        assertThat(p.pdfa).isTrue();
        String[] seiten = p.ergebnis.volltext().split("\f");
        assertThat(seiten).hasSizeGreaterThanOrEqualTo(3);
        assertThat(seiten[0]).contains("Alpha");
        assertThat(seiten[1]).contains("Beta");
        assertThat(seiten[2]).contains("Gamma");
        assertThat(p.vorschauNamen).containsExactly("vorschau-1.png", "vorschau-2.png", "vorschau-3.png");
    }

    @Test
    void pdfMitTextebeneOhneNeuesOcr() throws Exception {
        Pruefung p = verarbeiten(OcrEingabe.von(quelle(Quelltyp.PDF, OcrTestdateien.textPdf("Digitale Rechnung",
                "Textebene vorhanden"))));

        assertThat(p.pdfa).isTrue();
        assertThat(p.ergebnis.ocrAusgefuehrt()).isFalse();
        assertThat(p.ergebnis.volltext()).contains("Textebene vorhanden");
        assertThat(ocrmypdf().argumente()).contains("--skip-text");
    }

    /** Textebene nur aus Satzzeichen: pdftotext findet keinen Text, OCRmyPDF schon (Exit-Code 6) → mit --skip-text. */
    @Test
    void nichtErkannteTextebeneWirdMitSkipTextWiederholt() throws Exception {
        Pruefung p = verarbeiten(OcrEingabe.von(quelle(Quelltyp.PDF, OcrTestdateien.textPdf("... --- ... !!! ???"))));

        assertThat(p.pdfa).isTrue();
        assertThat(p.ergebnis.ocrAusgefuehrt()).isFalse();
        assertThat(befehle).filteredOn(b -> b.programm().equals("ocrmypdf")).hasSize(2)
                .extracting(b -> b.argumente().contains("--skip-text")).containsExactly(false, true);
    }

    @Test
    void signiertesPdfErhaeltPdfaUndOriginalBleibtUnveraendert() throws Exception {
        Path original = Files.createDirectories(BASIS.getParent().resolve("quellen")).resolve("signiert.pdf");
        Files.write(original, OcrTestdateien.signiertesPdf());
        String vorher = sha256(original);

        Pruefung p = verarbeiten(OcrEingabe.von(new OcrQuelle(Quelltyp.PDF, () -> Files.newInputStream(original))));

        assertThat(p.pdfa).isTrue();
        assertThat(p.ergebnis.signaturUngueltig()).isTrue();
        assertThat(ocrmypdf().argumente()).contains("--invalidate-digital-signatures");
        assertThat(sha256(original)).isEqualTo(vorher);
    }

    @Test
    void verschluesseltesPdfErgibtPdfaFehlt() throws Exception {
        Pruefung p = verarbeiten(OcrEingabe.von(quelle(Quelltyp.PDF, OcrTestdateien.verschluesseltesPdf())));

        assertThat(p.ergebnis.pdfa()).isEmpty();
        assertThat(p.ergebnis.pdfaFehlt()).map(PdfaFehlt::grund).contains(PdfaFehlt.Grund.VERSCHLUESSELT);
        assertThat(p.ergebnis.pdfaFehlt().orElseThrow().fehlertext()).contains("verschlüsselt");
        assertThat(p.ergebnis.volltext()).isEmpty();
    }

    @Test
    void arbeitsfassungenDerHandyErfassungWerdenSeitenEinesPdfa() throws Exception {
        Path quellen = Files.createDirectories(BASIS.getParent().resolve("quellen"));
        Path seite1 = Files.write(quellen.resolve("arbeitsfassung-1.jpg"),
                OcrTestdateien.jpeg(seite(0, "Arbeitsfassung Seite Eins")));
        Path seite2 = Files.write(quellen.resolve("arbeitsfassung-2.png"),
                OcrTestdateien.png(seite(0, "Arbeitsfassung Seite Zwei")));
        String sha1 = sha256(seite1);
        String sha2 = sha256(seite2);

        Pruefung p = verarbeiten(OcrEingabe.von(new OcrQuelle(Quelltyp.JPEG, () -> Files.newInputStream(seite1)),
                new OcrQuelle(Quelltyp.PNG, () -> Files.newInputStream(seite2))));

        assertThat(p.pdfa).isTrue();
        String[] seiten = p.ergebnis.volltext().split("\f");
        assertThat(seiten[0]).contains("Eins");
        assertThat(seiten[1]).contains("Zwei");
        assertThat(sha256(seite1)).isEqualTo(sha1);
        assertThat(sha256(seite2)).isEqualTo(sha2);
    }

    @Test
    void befehlszeilenEnthaltenNurFesteNamenImLaufverzeichnis() throws Exception {
        verarbeiten(OcrEingabe.von(quelle(Quelltyp.HEIC, OcrTestdateien.heic()),
                quelle(Quelltyp.PNG, OcrTestdateien.png(seite(0, "Zweites Bild"))),
                quelle(Quelltyp.JPEG, OcrTestdateien.jpeg(seite(0, "Drittes Bild")))));

        Pattern erlaubt = Pattern.compile("-[a-z-]*|[A-Za-z0-9+-]+|" + Pattern.quote(BASIS.toString())
                + "/lauf-[0-9]+/[a-z0-9.-]+");
        assertThat(befehle).isNotEmpty().allSatisfy(b -> assertThat(b.argumente())
                .allSatisfy(a -> assertThat(a).matches(erlaubt)));
    }

    @Test
    void laufverzeichnisWirdAuchNachFehlerGeloescht() throws Exception {
        byte[] kaputt = "kein PDF".getBytes();

        assertThatThrownBy(() -> adapter.verarbeiten(OcrEingabe.von(quelle(Quelltyp.PDF, kaputt)), e -> e))
                .isInstanceOf(OcrFehlgeschlagen.class)
                .satisfies(e -> assertThat(((OcrFehlgeschlagen) e).art()).isEqualTo(OcrFehlgeschlagen.Art.WERKZEUG))
                .hasMessageNotContaining("kein PDF");
        assertThat(laufverzeichnisse()).isEmpty();
    }

    private Befehl ocrmypdf() {
        return befehle.stream().filter(b -> b.programm().equals("ocrmypdf")).reduce((a, b) -> b).orElseThrow();
    }

    /** Prüft während der Verwertung (die Dateien bestehen nur dann) und danach, dass das Laufverzeichnis weg ist. */
    private Pruefung verarbeiten(OcrEingabe eingabe) throws Exception {
        Pruefung p = adapter.verarbeiten(eingabe, ergebnis -> {
            try {
                boolean pdfa = ergebnis.pdfa().isPresent() && PdfaPruefung.istPdfa2b(ergebnis.pdfa().get());
                for (Path vorschau : ergebnis.vorschaubilder()) {
                    assertThat(Files.readAllBytes(vorschau)).startsWith(0x89, 'P', 'N', 'G');
                }
                return new Pruefung(ergebnis, pdfa,
                        ergebnis.vorschaubilder().stream().map(v -> v.getFileName().toString()).toList());
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(laufverzeichnisse()).isEmpty();
        return p;
    }

    private static List<Path> laufverzeichnisse() throws IOException {
        if (!Files.exists(BASIS)) {
            return List.of();
        }
        try (Stream<Path> inhalt = Files.list(BASIS)) {
            return inhalt.toList();
        }
    }

    private static OcrQuelle quelle(Quelltyp typ, byte[] inhalt) {
        return new OcrQuelle(typ, () -> new ByteArrayInputStream(inhalt));
    }

    private static String sha256(Path datei) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(datei)));
    }

    private record Pruefung(OcrErgebnis ergebnis, boolean pdfa, List<String> vorschauNamen) {
    }
}
