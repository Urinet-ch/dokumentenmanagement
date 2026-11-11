package ch.urinet.archiv.gemeinsam.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Tabellentests zur Normalisierung nach design.md «Normalisierung» (Req 7.4); alle Werte sind erfunden. */
class NormalisiererTest {

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource(delimiter = '|', value = {
            // Umlaute, ß und Akzente
            "Müller Söhne Bäckerei | mueller soehne baeckerei",
            "MÜLLER                | mueller",
            "Strasse Grüßen        | strasse gruessen",
            "Café Crème            | cafe creme",
            "Ñandú Façade          | nandu facade",
            // kombinierende Zeichen und NFKC (Ligatur, Vollbreite, geschütztes Leerzeichen)
            "Mu\u0308ller          | mueller",
            "\uFB01lm Ｆｏｏ        | film foo",
            "'\tMuster\u00A0Holz'  | muster holz",
            // & und Satzzeichen
            "Meier & Co.           | meier und co",
            "Meier&Partner         | meier und partner",
            "A.-B. (Test) / Nord!  | a b test nord",
            "«Muster» – Holz       | muster holz",
            // Rechtsformen nur als ganzes Wort
            "Muster AG             | muster",
            "Muster GmbH           | muster",
            "Muster Sàrl           | muster",
            "Muster Sagl           | muster",
            "Muster SA             | muster",
            "Muster KlG            | muster",
            "Muster KG             | muster",
            "Muster Ltd.           | muster",
            "Muster Inc            | muster",
            "Verein Muster         | muster",
            "Stiftung Muster       | muster",
            "Genossenschaft Muster | muster",
            "Agrar Sagenhaft       | agrar sagenhaft",
            "Muster AG-Holding     | muster holding",
            // Leerraum
            "'  Muster    Holz  '  | muster holz",
            // nur Rechtsform oder Satzzeichen
            "AG                    | ''",
            "...                   | ''",
            "''                    | ''"})
    void name(String eingabe, String erwartet) {
        assertThat(Normalisierer.name(eingabe)).isEqualTo(erwartet);
    }

    @Test
    void nameOhneWertIstLeer() {
        assertThat(Normalisierer.name(null)).isEmpty();
    }

    @ParameterizedTest(name = "«{0}» «{1}» → «{2}»")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Jörg    | Muster   | joerg muster",
            "NULL    | Muster   | muster",
            "Jörg    | NULL     | joerg",
            "NULL    | NULL     | ''"})
    void personName(String vorname, String name, String erwartet) {
        assertThat(Normalisierer.personName(vorname, name)).isEqualTo(erwartet);
    }

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "CHE-123.456.789           | CHE123456789",
            "che 123 456 789           | CHE123456789",
            "CHE-123.456.789 MWST      | CHE123456789",
            "CHE-123.456.789 TVA       | CHE123456789",
            "CHE-123.456.789 IVA       | CHE123456789",
            "CHE-123.456.789 HR        | CHE123456789",
            "CHE123456789MWST          | CHE123456789",
            "'\u00A0CHE\u00A0123.456.789\u202FMWST\u00A0' | CHE123456789",
            "' CHE-123.456.789  mwst ' | CHE123456789",
            "DE 123 456 789            | DE123456789",
            "NULL                      | ''"})
    void uid(String eingabe, String erwartet) {
        assertThat(Normalisierer.uid(eingabe)).isEqualTo(erwartet);
    }

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "CH93 0076 2011 6238 5295 7 | CH9300762011623852957",
            "ch9300762011623852957      | CH9300762011623852957",
            "'  CH93\t0076 2011  '      | CH9300762011",
            "'CH93\u00A00076\u202F2011\u00A0' | CH9300762011",
            "NULL                       | ''"})
    void iban(String eingabe, String erwartet) {
        assertThat(Normalisierer.iban(eingabe)).isEqualTo(erwartet);
    }

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "' Info@Beispiel.CH '   | info@beispiel.ch",
            "MAX.MUSTER@EXAMPLE.ORG | max.muster@example.org",
            "'\u00A0Info@Beispiel.CH\u202F' | info@beispiel.ch",
            "NULL                   | ''"})
    void email(String eingabe, String erwartet) {
        assertThat(Normalisierer.email(eingabe)).isEqualTo(erwartet);
    }

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "1234        | 1234",
            "CH-1234     | 1234",
            "' 1 2 3 4 ' | 1234",
            "NULL        | ''"})
    void plz(String eingabe, String erwartet) {
        assertThat(Normalisierer.plz(eingabe)).isEqualTo(erwartet);
    }
}
