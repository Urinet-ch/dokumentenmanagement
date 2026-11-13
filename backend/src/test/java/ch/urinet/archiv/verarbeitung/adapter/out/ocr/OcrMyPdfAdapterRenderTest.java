package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.ki.adapter.out.bild.PpmLeser;
import ch.urinet.archiv.verarbeitung.application.OcrPort.Render;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aufrufe der Werkzeuge für die Modelleingabe (Review PR #93, M1 und M3) ohne Docker: ein Starter, der nur die
 * Befehle festhält und die erwarteten Dateien anlegt. Die Pixelgrenze bei MediaBox ≠ CropBox und am Rand (Review PR
 * #120) prüfen zwei Tests zusätzlich mit den echten Werkzeugen im Image der Stufe {@code werkzeuge}.
 */
class OcrMyPdfAdapterRenderTest {

    private static final double A4_BREITE = 595.276;
    private static final double A4_HOEHE = 841.89;
    private static final long PIXEL_MAX = 8_750_000;

    @TempDir
    Path arbeit;

    @Test
    void layoutTextWieImGoldenLauf() throws Exception {
        Fake starter = new Fake("");
        String text = adapter(starter).layoutText(pdf(), Duration.ofSeconds(30));

        assertThat(text).isEqualTo("Text mit  Layout");
        assertThat(starter.befehle).singleElement().satisfies(b -> {
            assertThat(b.programm()).isEqualTo("pdftotext");
            assertThat(b.argumente()).containsSubsequence("-layout", "-enc", "UTF-8");
        });
    }

    @Test
    void graustufigMit300DpiBeiA4UndDieLetzteSeite() throws Exception {
        Fake starter = new Fake(info(5, A4_BREITE, A4_HOEHE));
        List<Path> seiten = adapter(starter).seitenbilder(pdf(),
                new Render(2, false, PIXEL_MAX, Duration.ofSeconds(30)), List::copyOf);

        List<Befehl> render = starter.befehle.stream().filter(b -> b.programm().equals("pdftoppm")).toList();
        assertThat(render).hasSize(3).allSatisfy(b -> assertThat(b.argumente()).contains("-gray")
                .containsSubsequence("-r", "300").containsSubsequence("-tiff", "-tiffcompression", "lzw")
                .doesNotContain("-png"));
        assertThat(render).extracting(b -> b.argumente().get(b.argumente().indexOf("-f") + 1))
                .containsExactly("1", "2", "5");
        assertThat(seiten).hasSize(3);
    }

    @Test
    void farbigOhneGrayAlsPpm() throws Exception {
        Fake starter = new Fake(info(1, A4_BREITE, A4_HOEHE));
        List<Path> seiten = adapter(starter).seitenbilder(pdf(), new Render(3, true, PIXEL_MAX, Duration.ofSeconds(30)),
                List::copyOf);

        assertThat(seiten).singleElement().satisfies(p -> assertThat(p.toString()).endsWith(".ppm"));
        assertThat(starter.befehle).filteredOn(b -> b.programm().equals("pdftoppm")).singleElement()
                .satisfies(b -> assertThat(b.argumente()).doesNotContain("-gray").containsSubsequence("-r", "300")
                        .doesNotContain("-png", "-tiff", "-jpeg"));
    }

    @Test
    void grosseSeiteMitWenigerDpi() throws Exception {
        Fake starter = new Fake(info(1, 2 * A4_HOEHE, 2 * A4_BREITE * 2));
        adapter(starter).seitenbilder(pdf(), new Render(3, false, PIXEL_MAX, Duration.ofSeconds(30)), List::copyOf);

        Befehl render = starter.befehle.stream().filter(b -> b.programm().equals("pdftoppm")).findFirst()
                .orElseThrow();
        int dpi = Integer.parseInt(render.argumente().get(render.argumente().indexOf("-r") + 1));
        assertThat(dpi).isLessThan(300);
        assertThat((Math.ceil(2 * A4_HOEHE * dpi / 72) + 1) * (Math.ceil(4 * A4_BREITE * dpi / 72) + 1))
                .isLessThanOrEqualTo(PIXEL_MAX);
    }

    @Test
    void grosseMediaBoxBestimmtDieDpi() throws Exception {
        Fake starter = new Fake(info(1, A4_BREITE, A4_HOEHE, "0.00 0.00 2000.00 2000.00"));
        adapter(starter).seitenbilder(pdf(), new Render(3, true, PIXEL_MAX, Duration.ofSeconds(30)), List::copyOf);

        assertThat(starter.befehle).filteredOn(b -> b.programm().equals("pdfinfo")).singleElement()
                .satisfies(b -> assertThat(b.argumente()).contains("-box"));
        Befehl render = starter.befehle.stream().filter(b -> b.programm().equals("pdftoppm")).findFirst()
                .orElseThrow();
        assertThat(render.argumente()).doesNotContain("-cropbox");
        int dpi = Integer.parseInt(render.argumente().get(render.argumente().indexOf("-r") + 1));
        assertThat(dpi).isLessThan(300);
        assertThat((Math.ceil(2000.0 * dpi / 72) + 1) * (Math.ceil(2000.0 * dpi / 72) + 1)).isLessThanOrEqualTo(
                PIXEL_MAX);
    }

    @Test
    void seiteOhneMediaBoxWirdNichtGerendert() throws Exception {
        Fake starter = new Fake("Pages:          1\nPage    1 size: 595.276 x 841.89 pts (A4)\n");
        List<Path> seiten = adapter(starter).seitenbilder(pdf(),
                new Render(3, true, PIXEL_MAX, Duration.ofSeconds(30)), List::copyOf);

        assertThat(seiten).isEmpty();
        assertThat(starter.befehle).noneMatch(b -> b.programm().equals("pdftoppm"));
    }

    @Test
    void seiteninfoNimmtJeRichtungDasGroessereAusMediaBoxUndSize() {
        OcrMyPdfAdapter.Seiteninfo info = OcrMyPdfAdapter.Seiteninfo.lesen(
                info(1, A4_BREITE, A4_HOEHE, "-10.00 -10.00 600.00 850.00")
                        + info(1, A4_BREITE, A4_HOEHE, "0.00 0.00 300.00 2000.00").replace("Page    1", "Page    2"));

        assertThat(info.formate().get(1)).containsExactly(610.0, 860.0);
        assertThat(info.formate().get(2)).containsExactly(A4_BREITE, 2000.0);
    }

    @Test
    void mediaBoxGroesserAlsCropBoxBleibtMitEchtenWerkzeugenUnterDerGrenze() throws Exception {
        Path pdf = arbeit.resolve("mediabox.pdf");
        try (PDDocument dok = new PDDocument()) {
            PDPage seite = new PDPage(new PDRectangle(2000, 2000));
            seite.setCropBox(new PDRectangle(0, 0, (float) A4_BREITE, (float) A4_HOEHE));
            dok.addPage(seite);
            schwarzesFeld(dok, seite);
            dok.save(pdf.toFile());
        }

        List<PpmLeser.Kopf> koepfe = echteSeiten(pdf);

        assertThat(koepfe).singleElement().satisfies(k -> {
            assertThat(k.breite()).isGreaterThan(2000);
            assertThat((long) k.breite() * k.hoehe()).isLessThanOrEqualTo(PIXEL_MAX);
        });
    }

    @Test
    void randformatMitGerundetemPdfinfoBleibtMitEchtenWerkzeugenUnterDerGrenze() throws Exception {
        Path pdf = arbeit.resolve("rand.pdf");
        try (PDDocument dok = new PDDocument()) {
            PDPage seite = new PDPage(new PDRectangle(821, 1000.035f));
            dok.addPage(seite);
            schwarzesFeld(dok, seite);
            dok.save(pdf.toFile());
        }

        List<PpmLeser.Kopf> koepfe = echteSeiten(pdf);

        assertThat(koepfe).singleElement()
                .satisfies(k -> assertThat((long) k.breite() * k.hoehe()).isLessThanOrEqualTo(PIXEL_MAX));
    }

    @Test
    void dpiMitSpielraumFuerGerundetesFormat() {
        // Review PR #120: pdfinfo meldet 1000.03, pdftoppm rendert 1000.035; 235 dpi ergäbe 2680 × 3265 Pixel
        assertThat(OcrMyPdfAdapter.dpi(821, 1000.03, PIXEL_MAX)).hasValue(234);
    }

    @Test
    void dpiBleibtBeiA4UndSinktSonst() {
        assertThat(OcrMyPdfAdapter.dpi(A4_BREITE, A4_HOEHE, PIXEL_MAX)).hasValue(300);
        assertThat(OcrMyPdfAdapter.dpi(A4_BREITE * 4, A4_HOEHE * 4, PIXEL_MAX).getAsInt()).isLessThan(80);
        assertThat(OcrMyPdfAdapter.dpi(1e7, 1e7, PIXEL_MAX)).isEmpty();
        assertThat(OcrMyPdfAdapter.dpi(0, A4_HOEHE, PIXEL_MAX)).isEmpty();
        assertThat(OcrMyPdfAdapter.dpi(Double.NaN, A4_HOEHE, PIXEL_MAX)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void seiteninfoLiestSeitenUndFormate() {
        OcrMyPdfAdapter.Seiteninfo info = OcrMyPdfAdapter.Seiteninfo.lesen(info(3, A4_BREITE, A4_HOEHE));

        assertThat(info.seiten()).isEqualTo(3);
        assertThat(info.formate()).containsOnlyKeys(1, 2, 3);
        assertThat(info.formate().get(2)).containsExactly(A4_BREITE, A4_HOEHE);
    }

    private OcrMyPdfAdapter adapter(Werkzeugstarter starter) {
        OcrEinstellungen einstellungen = einstellungen();
        return new OcrMyPdfAdapter(einstellungen, starter, Clock.systemUTC());
    }

    private OcrEinstellungen einstellungen() {
        return new OcrEinstellungen(Duration.ofMinutes(1), 1, arbeit.resolve("ocr"), Optional.empty(), "docker",
                "/usr/bin");
    }

    private Path pdf() throws Exception {
        Path pdf = arbeit.resolve("eingabe.pdf");
        Files.writeString(pdf, "%PDF-1.7");
        return pdf;
    }

    private static String info(int seiten, double breite, double hoehe) {
        return info(seiten, breite, hoehe, "0.00 0.00 " + breite + " " + hoehe);
    }

    /** Ausgabe von {@code pdfinfo -box} mit derselben MediaBox auf jeder Seite. */
    private static String info(int seiten, double breite, double hoehe, String mediaBox) {
        StringBuilder s = new StringBuilder("Producer: Test\nPages:          " + seiten + "\n");
        for (int i = 1; i <= seiten; i++) {
            String seite = "Page " + String.format("%4d", i);
            s.append(seite).append(" size: ").append(breite).append(" x ").append(hoehe).append(" pts (A4)\n")
                    .append(seite).append(" rot:  0\n")
                    .append(seite).append(" MediaBox:     ").append(mediaBox).append('\n')
                    .append(seite).append(" CropBox:      0.00 0.00 ").append(breite).append(' ').append(hoehe)
                    .append('\n');
        }
        return s.toString();
    }

    private static void schwarzesFeld(PDDocument dok, PDPage seite) throws Exception {
        try (PDPageContentStream inhalt = new PDPageContentStream(dok, seite)) {
            inhalt.setNonStrokingColor(Color.BLACK);
            inhalt.addRect(50, 50, 200, 200);
            inhalt.fill();
        }
    }

    /** Rendert farbig mit den echten Werkzeugen und gibt die Köpfe der PPM-Seiten zurück. */
    private List<PpmLeser.Kopf> echteSeiten(Path pdf) throws Exception {
        OcrEinstellungen echt = new OcrEinstellungen(Duration.ofMinutes(5), 1,
                Path.of("target/ocr-arbeit/render").toAbsolutePath(), Optional.of(WerkzeugImage.sicherstellen()),
                "docker", "/usr/bin:/bin");
        return new OcrMyPdfAdapter(echt).seitenbilder(pdf, new Render(3, true, PIXEL_MAX, Duration.ofMinutes(5)),
                seiten -> {
                    List<PpmLeser.Kopf> koepfe = new ArrayList<>();
                    for (Path seite : seiten) {
                        koepfe.add(PpmLeser.kopf(seite, Long.MAX_VALUE));
                    }
                    return koepfe;
                });
    }

    /** Hält Befehle fest; {@code pdfinfo} liefert die Seiteninfo, {@code pdftoppm}/{@code pdftotext} eine Datei. */
    private final class Fake extends Werkzeugstarter {

        final List<Befehl> befehle = new ArrayList<>();
        private final String pdfinfo;

        Fake(String pdfinfo) {
            super(einstellungen(), Clock.systemUTC());
            this.pdfinfo = pdfinfo;
        }

        @Override
        Lauf ausfuehren(Befehl befehl, Path laufverzeichnis, Instant frist) {
            befehle.add(befehl);
            List<String> a = befehl.argumente();
            try {
                switch (befehl.programm()) {
                    case "pdfinfo" -> {
                        return new Lauf(0, pdfinfo);
                    }
                    case "pdftotext" -> Files.writeString(Path.of(a.getLast()), "Text mit  Layout",
                            StandardCharsets.UTF_8);
                    case "pdftoppm" -> Files.write(Path.of(a.getLast() + "-" + a.get(a.indexOf("-f") + 1) + (a.contains("-tiff") ? ".tif" : ".ppm")),
                            new byte[]{1});
                    default -> throw new IllegalStateException(befehl.programm());
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return new Lauf(0, "");
        }
    }
}
