package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheAusgabe;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 03, Aufgabe 11.3: Eingabe und Ereignisse von {@code POST /public/v1/chat}. */
class OeffentlicherChatResourceTest {

    @Test
    void liestFrageUndOptionaleGespraechsId() {
        UUID id = UUID.randomUUID();
        var eingabe = OeffentlicherChatResource.eingabe("{\"frage\":\"Hallo?\",\"gespraechId\":\"" + id + "\",\"neu\":1}");
        assertThat(eingabe.frage()).isEqualTo("Hallo?");
        assertThat(eingabe.gespraechId()).contains(id);
        assertThat(OeffentlicherChatResource.eingabe("{\"frage\":\"x\",\"gespraechId\":null}").gespraechId()).isEmpty();
    }

    @Test
    void ungueltigeEingabenSind400OhneWert() {
        for (String roh : new String[]{null, "", " ", "keinjson", "[]", "{}", "{\"frage\":1}", "{\"frage\":null}",
                "{\"frage\":\"x\",\"gespraechId\":5}", "{\"frage\":\"x\",\"gespraechId\":\"kaputt\"}"}) {
            assertThatThrownBy(() -> OeffentlicherChatResource.eingabe(roh)).as(String.valueOf(roh))
                    .isInstanceOfSatisfying(FachFehler.class, e -> {
                        assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG);
                        assertThat(e.getMessage()).doesNotContain("kaputt");
                    });
        }
    }

    @Test
    void ereignisseAlsJson() {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000001");
        assertThat(OeffentlicherChatResource.daten(new OeffentlicheAusgabe.Werkzeug("dokumente_suchen"), "H"))
                .isEqualTo("{\"werkzeug\":\"dokumente_suchen\"}");
        assertThat(OeffentlicherChatResource.daten(new OeffentlicheAusgabe.Text("Grüezi \"du\""), "H"))
                .isEqualTo("{\"delta\":\"Grüezi \\\"du\\\"\"}");
        assertThat(OeffentlicherChatResource.daten(new OeffentlicheAusgabe.Fertig(id), "Hinweis \"fest\""))
                .isEqualTo("{\"gespraechId\":\"" + id + "\",\"hinweis\":\"Hinweis \\\"fest\\\"\"}");
        assertThat(OeffentlicherChatResource.daten(new OeffentlicheAusgabe.Fehler("Fest", id), "H"))
                .isEqualTo("{\"meldung\":\"Fest\",\"gespraechId\":\"" + id + "\"}");
        assertThat(OeffentlicherChatResource.daten(new OeffentlicheAusgabe.Quellen(List.of(
                new OeffentlicheQuelle(id, "Titel", "BULLETIN", "Bulletin", "3/2071", 2071, "7-8"),
                new OeffentlicheQuelle(id, "Ohne", "MERKBLATT", "Merkblatt", null))), "H"))
                .isEqualTo("[{\"dokumentId\":\"" + id + "\",\"titel\":\"Titel\",\"reihe\":{\"code\":\"BULLETIN\","
                        + "\"name\":\"Bulletin\"},\"ausgabe\":\"3/2071\",\"jahr\":2071,\"fundstelle\":{\"seite\":\"7-8\"}},"
                        + "{\"dokumentId\":\"" + id + "\",\"titel\":\"Ohne\",\"reihe\":{\"code\":\"MERKBLATT\","
                        + "\"name\":\"Merkblatt\"},\"ausgabe\":null,\"jahr\":null,\"fundstelle\":null}]");
    }

    @Test
    void nurFertigUndFehlerBeendenDenStream() {
        UUID id = UUID.randomUUID();
        assertThat(new OeffentlicheAusgabe.Fertig(id).letzte()).isTrue();
        assertThat(new OeffentlicheAusgabe.Fehler("x", id).letzte()).isTrue();
        assertThat(new OeffentlicheAusgabe.Text("x").letzte()).isFalse();
        assertThat(new OeffentlicheAusgabe.Quellen(List.of()).letzte()).isFalse();
        assertThat(new OeffentlicheAusgabe.Werkzeug("x").letzte()).isFalse();
    }

    @Test
    void hinweisJeMandantMitNeutralemStandard() throws IOException {
        Config ausgeliefert = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(
                OeffentlicherChatResource.class.getResource("/application.properties"))).build();
        assertThat(OeffentlicherChatResource.hinweis(ausgeliefert, new MandantCode("urinet")))
                .contains("aus Dokumenten des Archivs", "Massgebend sind die verlinkten Originaldokumente")
                .doesNotContain("Verband");
        assertThat(OeffentlicherChatResource.hinweis(ausgeliefert, new MandantCode("vhf-gsk")))
                .isEqualTo("Antworten werden automatisch aus Verbandsdokumenten erstellt. Massgebend sind die verlinkten "
                        + "Originaldokumente; für verbindliche Auskünfte wenden Sie sich bitte an die Geschäftsstelle.");

        Config eigen = new SmallRyeConfigBuilder().withDefaultValues(Map.of(
                OeffentlicherChatResource.HINWEIS_STANDARD, "Standard",
                "archiv.mandant.urinet.chat-hinweis", " Eigener\n\u0007Text ",
                "archiv.mandant.vhf-gsk.chat-hinweis", " ")).build();
        assertThat(OeffentlicherChatResource.hinweis(eigen, new MandantCode("urinet"))).isEqualTo("Eigener Text");
        assertThat(OeffentlicherChatResource.hinweis(eigen, new MandantCode("vhf-gsk"))).isEqualTo("Standard");
    }
}
