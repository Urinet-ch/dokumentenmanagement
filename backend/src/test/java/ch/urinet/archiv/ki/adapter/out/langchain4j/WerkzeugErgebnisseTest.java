package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Abbildung eines Werkzeugaufrufs auf {@link ChatEreignis.WerkzeugErgebnis}: Name, Parameter, Dokumente, Datenstand. */
class WerkzeugErgebnisseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant JETZT = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static ChatEreignis.WerkzeugErgebnis abbilden(String name, String argumente, String antwort) {
        return WerkzeugErgebnisse.abbilden(JSON, name, argumente, antwort, () -> JETZT);
    }

    @Test
    void suchtreffer() {
        ChatEreignis.WerkzeugErgebnis e = abbilden("dokumente_suchen", "{\"frage\":\"Gondel\",\"anzahl\":5,\"neueste\":null}",
                "{\"treffer\":[{\"id\":\"" + A + "\",\"titel\":\"Rechnung A\",\"seiten\":\"7-8\","
                        + "\"auszug\":\"GEHEIMER TEXT\"},"
                        + "{\"id\":\"" + B + "\",\"titel\":\"Rechnung B\"}],\"gesamt\":2}");
        assertThat(e.name()).isEqualTo("dokumente_suchen");
        assertThat(e.parameter()).containsEntry("frage", "Gondel").containsEntry("anzahl", 5).doesNotContainKey("neueste");
        assertThat(e.dokumente()).extracting(ChatEreignis.QuellDokument::id).containsExactly(A, B);
        assertThat(e.dokumente()).extracting(ChatEreignis.QuellDokument::titel).containsExactly("Rechnung A", "Rechnung B");
        assertThat(e.dokumente()).extracting(ChatEreignis.QuellDokument::seite).containsExactly("7-8", null);
        assertThat(e.datenstand()).isEmpty();
        assertThat(e.toString()).doesNotContain("GEHEIMER TEXT");
    }

    @Test
    void seiteNurImGeprueftenFormatUebernehmen() {
        ChatEreignis.WerkzeugErgebnis e = abbilden("dokumente_suchen", "{}",
                "{\"treffer\":[{\"id\":\"" + A + "\",\"titel\":\"A\",\"seiten\":\"7-8\"},"
                        + "{\"id\":\"" + B + "\",\"titel\":\"B\",\"seiten\":\"http://example.test\"}]}");

        assertThat(e.dokumente()).extracting(ChatEreignis.QuellDokument::seite).containsExactly("7-8", null);
    }

    @Test
    void dokumentZeigen() {
        ChatEreignis.WerkzeugErgebnis e = abbilden("dokument_zeigen", "{\"id\":\"" + A + "\"}",
                "{\"id\":\"" + A + "\",\"titel\":\"Rechnung A\",\"inhalt\":\"GEHEIMER TEXT\"}");
        assertThat(e.dokumente()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(A);
            assertThat(d.titel()).isEqualTo("Rechnung A");
        });
        assertThat(e.toString()).doesNotContain("GEHEIMER TEXT");
    }

    @Test
    void fristenMitDatenstandAusDemFormatDesWerkzeugs() {
        ChatEreignis.WerkzeugErgebnis e = abbilden("fristen", "{\"von\":\"2071-01-01\",\"bis\":\"2071-12-31\"}",
                "{\"fristen\":[{\"datum\":\"31.03.2071\",\"dokument\":{\"id\":\"" + A + "\",\"titel\":\"Offerte\"}},"
                        + "{\"datum\":\"01.04.2071\",\"dokument\":{\"id\":\"" + A + "\",\"titel\":\"Offerte\"}}],"
                        + "\"gesamt\":2,\"datenstand\":{\"zeitpunkt\":\"14.11.2026 11:15\",\"anzahl\":2}}");
        assertThat(e.dokumente()).hasSize(1);
        assertThat(e.datenstand()).hasValueSatisfying(d -> {
            assertThat(d.belege()).isEqualTo(2);
            assertThat(d.zeitpunkt()).isEqualTo(Instant.parse("2026-11-14T10:15:00Z"));
        });
    }

    @Test
    void ungueltigerZeitpunktNimmtDieUhrDesAufrufs() {
        ChatEreignis.WerkzeugErgebnis e = abbilden("fristen", "{}", "{\"fristen\":[],\"datenstand\":{\"zeitpunkt\":\"?\",\"anzahl\":0}}");
        assertThat(e.datenstand()).hasValueSatisfying(d -> assertThat(d.zeitpunkt()).isEqualTo(JETZT));
    }

    @Test
    void fehlerUndKaputtesJsonErgebenKeineDokumente() {
        assertThat(abbilden("dokument_zeigen", "{\"id\":\"x\"}", "{\"fehler\":\"nicht gefunden\"}").dokumente()).isEmpty();
        assertThat(abbilden("fristen", "kein json", "kein json").dokumente()).isEmpty();
        assertThat(abbilden("fristen", "kein json", "kein json").parameter()).isEmpty();
        assertThat(abbilden("dokumente_suchen", "", "{\"treffer\":[{\"id\":\"keine-uuid\",\"titel\":\"X\"}]}").dokumente())
                .isEmpty();
    }
}
