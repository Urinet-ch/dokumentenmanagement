package ch.urinet.archiv.stapel.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QR-Inhalte der Trennblätter und Deckblätter (Spec 09, Req 6.2, 6.5; design.md «Trennblatt-Generator»). */
class TrennblattCodeTest {

    @Test
    void trennblattOhneUndMitArt() {
        assertThat(TrennblattCode.trenn(null).inhalt()).isEqualTo("URINET-TRENN;v=1");
        assertThat(TrennblattCode.trenn("QUITTUNG").inhalt()).isEqualTo("URINET-TRENN;v=1;art=QUITTUNG");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Versicherungen 2026|URINET-ORDNER;v=1;titel=Versicherungen%202026",
            "Bürokosten «Ä» 2026|URINET-ORDNER;v=1;titel=B%C3%BCrokosten%20%C2%AB%C3%84%C2%BB%202026",
            "Öl & Gas; A=B+C 100%|URINET-ORDNER;v=1;titel=%C3%96l%20%26%20Gas%3B%20A%3DB%2BC%20100%25",
            "Ordner 👩\u200D💻|URINET-ORDNER;v=1;titel=Ordner%20%F0%9F%91%A9%E2%80%8D%F0%9F%92%BB"})
    void titelUrlKodiertInUtf8(String titel, String inhalt) {
        TrennblattCode code = TrennblattCode.ordner(titel);

        assertThat(code.inhalt()).isEqualTo(inhalt).matches("\\p{ASCII}+");
        assertThat(TrennblattCode.lesen(inhalt)).contains(code);
        assertThat(TrennblattCode.lesen(inhalt).orElseThrow().titel()).isEqualTo(titel);
    }

    @Test
    void titelInNfcUndOhneLeerraumAussen() {
        TrennblattCode code = TrennblattCode.ordner("  Bu\u0308ro 2026  ");

        assertThat(code.titel()).isEqualTo("Büro 2026");
        assertThat(code.inhalt()).isEqualTo("URINET-ORDNER;v=1;titel=B%C3%BCro%202026");
    }

    @Test
    void sechzigZeichenNachNfcSindErlaubtEinundsechzigNicht() {
        assertThat(TrennblattCode.ordner("ä".repeat(60)).titel()).hasSize(60);
        assertThat(TrennblattCode.ordner("a\u0308".repeat(60)).titel()).isEqualTo("ä".repeat(60));
        assertThat(TrennblattCode.ordner("😀".repeat(60)).titel().codePointCount(0, 120)).isEqualTo(60);
        ungueltig("ä".repeat(61));
        ungueltig("😀".repeat(61));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Ordner\u0007", "Ordner\tA", "A\nB", "\u202EOrdner", "A\u2028B", "A\u2029B",
            "A\u200BB", "A\uFEFFB", "A\uD800B", "A\u0000"})
    void steuerUndFormatzeichenOderLeerSindUngueltig(String titel) {
        ungueltig(titel);
    }

    @Test
    void ohneTitelUngueltig() {
        ungueltig(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPC\n0200\n1\nCH4431999123000889012", "URINET-TRENN;v=2", "URINET-TRENN;v=1;art=quittung",
            "URINET-TRENN;v=1;art=", "URINET-TRENN;v=1;foo=bar", "URINET-TRENN;v=1;art=QUITTUNG;x=1", "URINET-TRENN",
            "URINET-ORDNER;v=1", "URINET-ORDNER;v=1;titel=", "URINET-ORDNER;v=1;titel=%ZZ",
            "URINET-ORDNER;v=1;titel=A%07", "URINET-ORDNER;v=1;titel=%20A", "urinet-trenn;v=1", "URINET-TRENN;V=1"})
    void fremdeOderFalscheInhalteSindKeinCode(String inhalt) {
        assertThat(TrennblattCode.lesen(inhalt)).isEmpty();
    }

    @Test
    void lesenErkenntTrennblaetter() {
        assertThat(TrennblattCode.lesen("URINET-TRENN;v=1")).contains(TrennblattCode.trenn(null));
        assertThat(TrennblattCode.lesen("URINET-TRENN;v=1;art=QUITTUNG")).contains(TrennblattCode.trenn("QUITTUNG"));
        assertThat(TrennblattCode.lesen("URINET-ORDNER;v=1;titel=" + "a".repeat(61))).isEmpty();
        assertThat(TrennblattCode.lesen(null)).isEmpty();
    }

    @Test
    void felderDesAnderenTypsSindEinProgrammfehler() {
        assertThatThrownBy(() -> new TrennblattCode(TrennblattCode.Typ.TRENN, null, "Titel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrennblattCode(TrennblattCode.Typ.ORDNER, "QUITTUNG", "Titel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrennblattCode.trenn("A;B")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void ungueltig(String titel) {
        assertThatThrownBy(() -> TrennblattCode.ordner(titel)).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.TRENNBLATT_UNGUELTIG));
    }
}
