package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErfassungsMetadatenTest {
    private final ErfassungsMetadaten parser = new ErfassungsMetadaten(
            JsonMapper.builder().addModule(new JavaTimeModule()).build());

    @Test
    void unsichtbareDubletteGibtNurReferenzUndDublettenkennzeichenAus() {
        var dto = WebseitenErfassungResource.uploadDto(
                new ch.urinet.archiv.integration.application.webseite.WebseitenErfassung.Erfassungsergebnis(
                        null, "probe", true, false));
        var antwort = JsonMapper.builder().build().valueToTree(dto);
        assertThat(antwort.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("referenz", "dublette");
        assertThat(antwort.path("dublette").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"dateiname\":null}", "{\"status\":\"ARCHIVIERT\"}",
            "{\"veroeffentlicht\":true}", "{\"zusatz\":{\"veroeffentlicht\":false}}",
            "{\"zusatz\":{\"veroeffentlicht\":null}}", "{\"titel\":1}", "{\"tags\":[1]}",
            "{\"geschaeftsjahr\":\"2026\"}", "{\"titel\":\"a\",\"titel\":\"b\"}", "{\"titel\":\"a\"}{}"})
    void unerlaubteFelderTypenUndMehrdeutigesJsonSindRot(String eingabe) {
        for (boolean bestaetigen : new boolean[]{false, true}) {
            assertThatThrownBy(() -> parser.lesen(eingabe, bestaetigen))
                    .isInstanceOfSatisfying(FachFehler.class,
                            f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
    }

    @Test
    void personenzuordnungIstNurBeimBestaetigenWieInDerWebAppErlaubt() {
        String eingabe = "{\"personBenutzer\":\"person-probe\"}";
        assertThatThrownBy(() -> parser.lesen(eingabe, false)).isInstanceOf(FachFehler.class);
        assertThat(parser.bestaetigung(parser.lesen(eingabe, true)).personBenutzer()).isEqualTo("person-probe");
    }

    @Test
    void patchUnterscheidetFehlendVonNullUndBehaeltDezimalzahlen() {
        var a = parser.aenderung(parser.lesen("""
                {"titel":null,"betrag":12.35,"zusatz":{"zugriff":"vorstand","liste":[]}}
                """, false));
        assertThat(a.gesetzt()).containsExactlyInAnyOrder("titel", "betrag", "zusatz");
        assertThat(a.titel()).isNull();
        assertThat(a.betrag()).isEqualByComparingTo("12.35");
        assertThat(a.zusatzFelder()).containsExactly("zugriff");
    }

    @Test
    void bestaetigungNimmtZusatzSeparatUndKeineInternenFelder() {
        var k = parser.lesen("{\"art\":\"BULLETIN\",\"titel\":\"Test\",\"zusatz\":{\"jahr\":2026}}", true);
        assertThat(parser.bestaetigung(k).art()).isEqualTo("BULLETIN");
        assertThat(parser.bestaetigung(k).personBenutzer()).isNull();
    }
}
