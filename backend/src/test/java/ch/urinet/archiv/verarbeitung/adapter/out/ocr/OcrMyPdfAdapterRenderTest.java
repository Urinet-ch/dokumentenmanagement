package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.verarbeitung.application.OcrPort.Render;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Befehle festhält und die erwarteten Dateien anlegt.
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
        assertThat(Math.ceil(2 * A4_HOEHE * dpi / 72) * Math.ceil(4 * A4_BREITE * dpi / 72)).isLessThanOrEqualTo(
                PIXEL_MAX);
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
        StringBuilder s = new StringBuilder("Producer: Test\nPages:          " + seiten + "\n");
        for (int i = 1; i <= seiten; i++) {
            s.append("Page ").append(String.format("%4d", i)).append(" size: ").append(breite).append(" x ")
                    .append(hoehe).append(" pts (A4)\n");
        }
        return s.toString();
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
