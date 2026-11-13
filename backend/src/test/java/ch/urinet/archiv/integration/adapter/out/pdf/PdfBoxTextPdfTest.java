package ch.urinet.archiv.integration.adapter.out.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** PDF aus dem Text einer Mail (Spec 02, Req 1.3): Kopf und Text lesbar, fremde Zeichen als «?», lange Texte umbrochen. */
class PdfBoxTextPdfTest {

    @TempDir
    Path verzeichnis;

    @Test
    void kopfUndTextStehenImPdfZeichenAusserhalbVonWinAnsiWerdenFragezeichen() throws Exception {
        Path pdf = verzeichnis.resolve("text.pdf");
        String lang = "Wort ".repeat(3000) + "x".repeat(400);
        new PdfBoxTextPdf().erzeugen(List.of("Von: inhaber@example.invalid", "Betreff: Grüsse 😀"),
                "Grüezi\tmitenand\r\nZweite Zeile ✓ €\n" + lang, pdf);
        try (PDDocument dokument = Loader.loadPDF(pdf.toFile())) {
            assertThat(dokument.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(dokument);
            assertThat(text).contains("Von: inhaber@example.invalid", "Betreff: Grüsse ?", "Grüezi mitenand",
                    "Zweite Zeile ? €");
        }
    }

    @Test
    void einWortAus100000ZeichenWirdInLinearerZeitHartGetrennt() throws Exception {
        Path pdf = verzeichnis.resolve("lang.pdf");
        String wort = "W".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> new PdfBoxTextPdf().erzeugen(List.of(), wort, pdf));
        try (PDDocument dokument = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(dokument).replaceAll("\\s", "");
            assertThat(text).hasSize(100_000).matches("W+");
        }
    }

    @Test
    void ohneTextEntstehtEineLeereSeite() throws Exception {
        Path pdf = verzeichnis.resolve("leer.pdf");
        new PdfBoxTextPdf().erzeugen(List.of(), "", pdf);
        try (PDDocument dokument = Loader.loadPDF(pdf.toFile())) {
            assertThat(dokument.getNumberOfPages()).isEqualTo(1);
        }
    }
}
