package ch.urinet.archiv.ablage.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Liste der inline anzeigbaren Typen ist eine Sicherheitsinvariante (Req 14.6): aktive Inhalte nie inline. */
class AnsichtTest {

    @ParameterizedTest
    @CsvSource({"pdf,application/pdf", "jpg,image/jpeg", "jpeg,image/jpeg", "png,image/png", "tif,image/tiff",
            "tiff,image/tiff"})
    void anzeigbareTypenHabenIhrenTyp(String endung, String typ) {
        assertThat(Ansicht.anzeigbarerTyp(DateiSchluessel.original(2026, UUID.randomUUID(), 1, endung))).hasValue(typ);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "htm", "svg", "js", "xml", "xhtml", "exe", "txt", "docx"})
    void aktiveUndUnbekannteTypenSindNieAnzeigbar(String endung) {
        assertThat(Ansicht.anzeigbarerTyp(DateiSchluessel.original(2026, UUID.randomUUID(), 1, endung))).isEmpty();
        assertThat(Ansicht.typVonSchluessel("archiv/2026/x/1-original." + endung)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"x/1-original.PDF,application/pdf", "x/1-original.TIFF,image/tiff", "x/1-original.JpEg,image/jpeg"})
    void endungOhneBeachtungVonGrossUndKleinschreibung(String schluessel, String typ) {
        assertThat(Ansicht.typVonSchluessel(schluessel)).hasValue(typ);
    }

    @Test
    void schluesselOhneEndungHatKeinenTyp() {
        assertThat(Ansicht.typVonSchluessel("x/ohne-endung")).isEmpty();
        assertThat(Ansicht.typVonSchluessel("x/1-original.html")).isEmpty();
    }
}
