package ch.urinet.archiv.ki.adapter.json;

import ch.urinet.archiv.ki.domain.chat.ChatAusgabe;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.Quelle;
import ch.urinet.archiv.ki.domain.chat.WerkzeugAufruf;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Serialisierung der Ereignisse des Streams und der Spalten (design.md «Vertrag Welle 7»). */
class ChatJsonTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID GESPRAECH = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final ChatEreignis.Datenstand STAND =
            new ChatEreignis.Datenstand(Instant.parse("2026-11-14T08:00:00Z"), 3);

    @Test
    void ereignisseDesStreams() {
        assertThat(name(new ChatAusgabe.Werkzeug("kennzahl"))).isEqualTo("werkzeug {\"name\":\"kennzahl\"}");
        assertThat(name(new ChatAusgabe.Text("Grüezi \"Welt\"\n"))).isEqualTo("text {\"delta\":\"Grüezi \\\"Welt\\\"\\n\"}");
        assertThat(name(new ChatAusgabe.Quellen(List.of(new Quelle.Dokument(ID, "Rechnung 2025-007"),
                new Quelle.Stand("kennzahl", STAND))))).isEqualTo("quellen [{\"id\":\"" + ID
                + "\",\"titel\":\"Rechnung 2025-007\",\"link\":\"/dokument/" + ID + "\"},"
                + "{\"werkzeug\":\"kennzahl\",\"datenstand\":\"2026-11-14T08:00:00Z\",\"belege\":3}]");
        assertThat(name(new ChatAusgabe.Fertig(GESPRAECH, Long.MAX_VALUE, new ChatEreignis.Ende(120, 30))))
                .isEqualTo("fertig {\"gespraechId\":\"" + GESPRAECH
                        + "\",\"nachrichtId\":\"9223372036854775807\",\"tokens\":{\"ein\":120,\"aus\":30}}");
        assertThat(name(new ChatAusgabe.Fehler("nicht verfügbar", GESPRAECH)))
                .isEqualTo("fehler {\"meldung\":\"nicht verfügbar\",\"gespraechId\":\"" + GESPRAECH + "\"}");
    }

    @Test
    void nurFertigUndFehlerBeendenDenStream() {
        assertThat(new ChatAusgabe.Fertig(GESPRAECH, 1, new ChatEreignis.Ende(0, 0)).letzte()).isTrue();
        assertThat(new ChatAusgabe.Fehler("x", GESPRAECH).letzte()).isTrue();
        assertThat(new ChatAusgabe.Quellen(List.of()).letzte()).isFalse();
        assertThat(new ChatAusgabe.Text("x").letzte()).isFalse();
        assertThat(new ChatAusgabe.Werkzeug("x").letzte()).isFalse();
    }

    @Test
    void quellenHinUndZurueck() {
        List<Quelle> quellen = List.of(new Quelle.Dokument(ID, "Vertrag"), new Quelle.Stand("fristen", STAND));
        assertThat(ChatJson.quellenLesen(ChatJson.quellen(quellen))).isEqualTo(quellen);
        assertThat(ChatJson.quellenLesen(null)).isEmpty();
    }

    @Test
    void wissensquelleMitDirektenBelegenBleibtInStreamUndVerlaufErhalten() {
        Quelle.Wissen wissen = new Quelle.Wissen("konzepte/arbeit", "Arbeitsregel",
                Instant.parse("2026-11-14T08:00:00Z"), List.of(new Quelle.Dokument(ID, "Vertrag", "2-3")));
        String json = ChatJson.quellen(List.of(wissen));

        assertThat(json).contains("\"wissen\":", "\"link\":\"/wissen/konzepte/arbeit\"",
                "\"dokumente\":", "\"seiten\":\"2-3\"");
        assertThat(ChatJson.quellenLesen(json)).containsExactly(wissen);
        assertThat(ChatJson.daten(new ChatAusgabe.Quellen(List.of(wissen)))).isEqualTo(json);
    }

    @Test
    void werkzeugeNurMitIdsUndParametern() {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("messgroesse", "EINNAHMEN");
        parameter.put("von", "2025-01-01");
        parameter.put("anzahl", 5);
        parameter.put("partner", null);
        String json = ChatJson.werkzeuge(List.of(
                new WerkzeugAufruf("kennzahl", parameter, List.of(ID), Optional.of(STAND)),
                new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(), Optional.empty())));
        assertThat(json).isEqualTo("[{\"name\":\"kennzahl\",\"parameter\":{\"messgroesse\":\"EINNAHMEN\","
                + "\"von\":\"2025-01-01\",\"anzahl\":5,\"partner\":null},\"dokumentIds\":[\"" + ID + "\"],"
                + "\"datenstand\":{\"zeitpunkt\":\"2026-11-14T08:00:00Z\",\"belege\":3}},"
                + "{\"name\":\"dokumente_suchen\",\"parameter\":{},\"dokumentIds\":[]}]");
    }

    @Test
    void kontextstufeWirdFuerSpaetereAnalyseGespeichert() {
        var aufruf = new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(ID), Optional.empty(),
                Optional.of(ChatEreignis.Kontextstufe.INTERN));
        assertThat(ChatJson.werkzeuge(List.of(aufruf))).contains("\"kontextstufe\":\"INTERN\"");
    }

    @Test
    void suchwegWirdMitWerkzeugaufrufGespeichert() {
        var aufruf = new WerkzeugAufruf("dokumente_suchen", Map.of(), List.of(ID), Optional.empty(),
                Optional.empty(), "VOLLTEXT", Map.of(ID, 0.87));
        assertThat(ChatJson.werkzeuge(List.of(aufruf)))
                .contains("\"suchweg\":\"VOLLTEXT\"", "\"aehnlichkeiten\":{\"" + ID + "\":0.87}");
    }

    private static String name(ChatAusgabe ausgabe) {
        return ausgabe.name() + " " + ChatJson.daten(ausgabe);
    }
}
