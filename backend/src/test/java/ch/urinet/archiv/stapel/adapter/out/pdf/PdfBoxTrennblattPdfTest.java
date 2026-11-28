package ch.urinet.archiv.stapel.adapter.out.pdf;

import ch.urinet.archiv.stapel.TrennblattPdfLeser;
import ch.urinet.archiv.stapel.domain.TrennblattCode;
import ch.urinet.archiv.stapel.domain.TrennblattSatz;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** PDF der Trennblätter (Spec 09, Req 6.2, 6.3): A4, beidseitig gleich, QR lesbar und gross genug, Schrift eingebettet. */
class PdfBoxTrennblattPdfTest {

    private final PdfBoxTrennblattPdf pdf = new PdfBoxTrennblattPdf();

    @Test
    void jedesBlattHatZweiGleicheSeitenMitLesbaremQrCode() throws IOException {
        TrennblattCode ordner = TrennblattCode.ordner("Bürokosten «Ä» 2026");
        byte[] datei = pdf.erzeugen(List.of(
                new TrennblattSatz(TrennblattCode.trenn(null), null, 1),
                new TrennblattSatz(TrennblattCode.trenn("QUITTUNG"), "Quittung / Spesen", 2),
                new TrennblattSatz(ordner, ordner.titel(), 1)));

        List<TrennblattPdfLeser.Seite> seiten = TrennblattPdfLeser.seiten(datei);

        assertThat(seiten).extracting(TrennblattPdfLeser.Seite::qr).containsExactly(
                "URINET-TRENN;v=1", "URINET-TRENN;v=1",
                "URINET-TRENN;v=1;art=QUITTUNG", "URINET-TRENN;v=1;art=QUITTUNG",
                "URINET-TRENN;v=1;art=QUITTUNG", "URINET-TRENN;v=1;art=QUITTUNG",
                "URINET-ORDNER;v=1;titel=B%C3%BCrokosten%20%C2%AB%C3%84%C2%BB%202026",
                "URINET-ORDNER;v=1;titel=B%C3%BCrokosten%20%C2%AB%C3%84%C2%BB%202026");
        for (TrennblattPdfLeser.Seite seite : seiten) {
            assertThat(seite.breitePt()).isCloseTo(595.28f, within(0.1f));
            assertThat(seite.hoehePt()).isCloseTo(841.89f, within(0.1f));
            assertThat(seite.kanteMm()).isCloseTo(60, within(1.5));
            assertThat(seite.korrektur()).isIn("M", "Q", "H");
            assertThat(seite.text()).contains(PdfBoxTrennblattPdf.HINWEIS);
        }
        assertThat(seiten.get(0).text()).contains("TRENNBLATT", PdfBoxTrennblattPdf.OHNE_ART);
        assertThat(seiten.get(3).text()).contains("TRENNBLATT", "Quittung / Spesen", "Art QUITTUNG");
        assertThat(seiten.get(7).text()).contains("ORDNER", "Bürokosten «Ä» 2026");
        assertThat(TrennblattPdfLeser.schriften(datei)).isNotEmpty()
                .allSatisfy(s -> assertThat(s).matches("[A-Z]{6}\\+LiberationSans eingebettet"));
    }

    @Test
    void auchDerLaengsteTitelBleibtBei150DpiLesbar() throws IOException {
        for (String titel : List.of("€".repeat(60), "ẞ".repeat(60), "ж".repeat(20), "ш".repeat(20), "Ω".repeat(40),
                "Versicherungen und Verträge der Liegenschaft Musterweg 1")) {
            TrennblattCode code = TrennblattCode.ordner(titel.substring(0, titel.offsetByCodePoints(0,
                    Math.min(60, titel.codePointCount(0, titel.length())))));
            byte[] datei = pdf.erzeugen(List.of(new TrennblattSatz(code, code.titel(), 1)));

            List<TrennblattPdfLeser.Seite> seiten = TrennblattPdfLeser.seiten(datei);

            assertThat(seiten).hasSize(2).allSatisfy(seite -> {
                assertThat(seite.qr()).isEqualTo(code.inhalt());
                assertThat(seite.kanteMm()).isGreaterThanOrEqualTo(40);
            });
            assertThat(TrennblattCode.lesen(seiten.get(0).qr())).contains(code);
        }
    }

    @Test
    void darstellbarIstNurWasDieSchriftAbdeckt() {
        assertThat(pdf.darstellbar("Bürokosten «Ä» – 2026 € ẞ Ελληνικά Кириллица")).isTrue();
        assertThat(pdf.darstellbar("档案 2026")).isFalse();
        assertThat(pdf.darstellbar("Ordner 😀")).isFalse();
        assertThat(pdf.darstellbar("Team 👩\u200D💻")).isFalse();
    }

    @Test
    void derNameEinerArtOhneGlypheErscheintAlsFragezeichenMitCode() throws IOException {
        byte[] datei = pdf.erzeugen(List.of(new TrennblattSatz(TrennblattCode.trenn("PROBE_ART"), "Probe 😀", 1)));

        TrennblattPdfLeser.Seite seite = TrennblattPdfLeser.seiten(datei).get(0);

        assertThat(seite.text()).contains("Probe ?", "Art PROBE_ART");
        assertThat(seite.qr()).isEqualTo("URINET-TRENN;v=1;art=PROBE_ART");
    }

    /**
     * Die Oberfläche prüft den Titel schon bei der Eingabe mit den Bereichen aus {@code schrift-abdeckung.ts}; sie
     * müssen genau der Unicode-Tabelle der eingebetteten Schrift entsprechen.
     */
    @Test
    void dieBereicheDerOberflaecheEntsprechenDerSchrift() throws IOException {
        Matcher bereich = Pattern.compile("\\[0x([0-9A-F]+), 0x([0-9A-F]+)]")
                .matcher(Files.readString(Path.of("../frontend/src/app/trennblaetter/schrift-abdeckung.ts")));
        BitSet oberflaeche = new BitSet();
        while (bereich.find()) {
            oberflaeche.set(Integer.parseInt(bereich.group(1), 16), Integer.parseInt(bereich.group(2), 16) + 1);
        }
        BitSet schrift = new BitSet();
        for (int c = 0; c <= Character.MAX_CODE_POINT; c++) {
            if (pdf.darstellbar(Character.toString(c))) {
                schrift.set(c);
            }
        }
        assertThat(oberflaeche.cardinality()).isPositive();
        assertThat(oberflaeche).isEqualTo(schrift);
    }

    @Test
    void hundertBlaetterBleibenKlein() throws IOException {
        byte[] datei = pdf.erzeugen(List.of(new TrennblattSatz(TrennblattCode.trenn("QUITTUNG"), "Quittung", 100)));

        try (var dokument = org.apache.pdfbox.Loader.loadPDF(datei)) {
            assertThat(dokument.getNumberOfPages()).isEqualTo(200);
        }
        assertThat(datei.length).isLessThan(512 * 1024);
    }
}
