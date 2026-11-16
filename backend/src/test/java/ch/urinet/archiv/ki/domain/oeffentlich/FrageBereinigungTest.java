package ch.urinet.archiv.ki.domain.oeffentlich;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 03, Req 6.5: Was von einer Frage gespeichert wird, enthält keine erkennbaren Personenangaben. */
class FrageBereinigungTest {

    @Test
    void entferntEmailTelefonUndLangeZifferfolgen() {
        String frage = FrageBereinigung.fuerSpeicher("Schreiben Sie an max.muster@example.invalid oder rufen Sie "
                + "079 123 45 67 an, IBAN CH93 0076 2011 6238 5295 7, AHV 756.1234.5678.97, Karte 4111-1111-1111-1111.");
        assertThat(frage).doesNotContain("max.muster", "079", "CH93", "0076", "756.1234", "4111")
                .contains("Schreiben Sie an", "[entfernt]");
    }

    @Test
    void entferntWebAdressenUndIpAdressen() {
        assertThat(FrageBereinigung.fuerSpeicher("Siehe https://beispiel.invalid/pfad?x=1 und www.beispiel.invalid von 192.0.2.77"))
                .doesNotContain("beispiel.invalid", "192.0.2.77");
    }

    @Test
    void laesstFachlichesStehen() {
        assertThat(FrageBereinigung.fuerSpeicher("Was steht in Bulletin 3/2071 zur Seilbahnrevision im Jahr 2071?"))
                .isEqualTo("Was steht in Bulletin 3/2071 zur Seilbahnrevision im Jahr 2071?");
    }

    @Test
    void entferntSteuerzeichenUndKuerztAufDieGrenze() {
        assertThat(FrageBereinigung.fuerSpeicher("a\u0000b\n\nc‮d")).isEqualTo("a b c d");
        String lang = FrageBereinigung.fuerSpeicher("x".repeat(2_000));
        assertThat(lang.codePointCount(0, lang.length())).isEqualTo(FrageBereinigung.MAX_ZEICHEN);
    }
}
