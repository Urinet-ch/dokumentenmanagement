package ch.urinet.archiv.dokument.domain;

import ch.urinet.archiv.dokument.Testdateien;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Grobe Strukturprüfung beim Upload (Req 3.7). */
class DateipruefungTest {

    @ParameterizedTest
    @CsvSource({"PDF,pdf", "JPEG,jpeg", "PNG,png", "HEIC,heic", "HEIF,heif", "TIFF,tiff"})
    void gueltigeDateiIstLesbar(Dateityp typ, String datei) {
        assertThat(lesbar(typ, Testdateien.erzeugen(datei))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"PDF,pdf", "JPEG,jpeg", "PNG,png", "HEIC,heic", "HEIF,heif", "TIFF,tiff"})
    void abgeschnitteneDateiIstNichtLesbar(Dateityp typ, String datei) {
        byte[] ganz = Testdateien.erzeugen(datei);
        // HEIC/HEIF und TIFF: abgeschnitten innerhalb der ftyp-Box bzw. des ersten IFD
        int laenge = switch (typ) {
            case HEIC, HEIF, TIFF -> 20;
            default -> ganz.length / 2;
        };
        assertThat(lesbar(typ, Arrays.copyOf(ganz, laenge))).isFalse();
    }

    @Test
    void leereDateiIstNichtLesbar() {
        assertThat(Dateipruefung.lesbar(Dateityp.PDF, new byte[0], new byte[0], 0)).isFalse();
    }

    @Test
    void pdfOhneEndmarkerIstNichtLesbar() {
        assertThat(lesbar(Dateityp.PDF, Testdateien.abgeschnitten())).isFalse();
    }

    @Test
    void falscherKopfIstNichtLesbar() {
        assertThat(lesbar(Dateityp.PNG, Testdateien.pdf())).isFalse();
        assertThat(lesbar(Dateityp.TIFF, Testdateien.jpeg())).isFalse();
    }

    @Test
    void angehaengteMetadatenNachDemJpegEndeStoerenNicht() {
        byte[] jpeg = Testdateien.jpeg();
        byte[] mitAnhang = Arrays.copyOf(jpeg, jpeg.length + 1000);
        assertThat(lesbar(Dateityp.JPEG, mitAnhang)).isTrue();
    }

    private static boolean lesbar(Dateityp typ, byte[] daten) {
        byte[] kopf = Arrays.copyOf(daten, Math.min(Dateipruefung.KOPF, daten.length));
        int ende = Math.min(Dateipruefung.ENDE, daten.length);
        return Dateipruefung.lesbar(typ, kopf, Arrays.copyOfRange(daten, daten.length - ende, daten.length),
                daten.length);
    }
}
