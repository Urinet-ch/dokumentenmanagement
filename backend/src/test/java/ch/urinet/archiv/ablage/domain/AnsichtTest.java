package ch.urinet.archiv.ablage.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** Der Dateiname im Kopf {@code Content-Disposition} entsteht nur aus UUID und festem Namen, nie aus Eingaben. */
    @ParameterizedTest
    @CsvSource({"ORIGINAL,pdf", "ORIGINAL,tiff", "ORIGINAL,docx", "ARBEITSFASSUNG,jpg", "PDFA,pdf"})
    void dateinameEnthaeltNurUuidUndSicheresMuster(DateiRolle rolle, String endung) {
        UUID docId = UUID.randomUUID();
        DateiSchluessel schluessel = new DateiSchluessel(2026, docId, rolle, rolle == DateiRolle.PDFA ? 0 : 7, endung);

        assertThat(Ansicht.dateiname(schluessel)).startsWith(docId + "-")
                .matches(Pattern.compile("[0-9a-f-]{36}-([0-9]{1,4}-)?[a-z]+\\.[a-z0-9]+"));
    }

    /** Eine Endung mit Anführungszeichen, Zeilenumbruch, Semikolon oder Pfadteil kommt nie in einen Schlüssel. */
    @ParameterizedTest
    @ValueSource(strings = {"pdf\"", "pdf\r\nSet-Cookie: a=b", "pdf\n", "pdf;x", "../x", "a/b", "p df", "",
            "abcdefghijk", "pdf\u0000", "pdf\u202e"})
    void endungMitSonderzeichenWirdAlsSchluesselAbgelehnt(String endung) {
        assertThatThrownBy(() -> DateiSchluessel.original(2026, UUID.randomUUID(), 1, endung))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Ansicht.typVonSchluessel("x/1-original." + endung)).isEmpty();
    }

    @Test
    void endungOhneEndungspunktOderMitPunktImOrdnerLiefertKeinenTyp() {
        assertThat(Ansicht.typVonSchluessel("archiv/2026.pdf/ohne-endung")).isEmpty();
        assertThat(Ansicht.typVonSchluessel("x/1-original.")).isEmpty();
        assertThat(Ansicht.typVonSchluessel("x/1-original.pdf.html")).isEmpty();
        assertThat(Ansicht.typVonSchluessel("x/1-original.html.pdf")).hasValue("application/pdf");
    }
}
