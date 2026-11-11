package ch.urinet.archiv.dokument.adapter.out.tika;

import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.domain.Dateityp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Typ am Inhalt, nicht an der Endung (Req 3.3). */
class TikaTyperkennungTest {

    private final TikaTyperkennung erkennung = new TikaTyperkennung();

    @TempDir
    Path ordner;

    @ParameterizedTest
    @CsvSource({"pdf,PDF", "jpeg,JPEG", "png,PNG", "heic,HEIC", "heif,HEIF", "tiff,TIFF"})
    void erkenntDenTypAmInhalt(String datei, Dateityp erwartet) throws IOException {
        // Endung absichtlich falsch: Sie zählt nicht
        Path pfad = Files.write(ordner.resolve("beleg.txt"), Testdateien.erzeugen(datei));
        assertThat(erkennung.erkennen(pfad)).contains(erwartet);
    }

    @Test
    void textMitPdfEndungIstKeinPdf() throws IOException {
        Path pfad = Files.write(ordner.resolve("beleg.pdf"), Testdateien.erzeugen("text"));
        assertThat(erkennung.erkennen(pfad)).isEmpty();
    }

    @Test
    void zipIstKeinErlaubterTyp() throws IOException {
        Path pfad = Files.write(ordner.resolve("notiz.pdf"), new byte[]{'P', 'K', 3, 4, 20, 0, 0, 0, 0, 0});
        assertThat(erkennung.erkennen(pfad)).isEmpty();
    }
}
