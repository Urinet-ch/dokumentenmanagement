package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.Pruefliste.Frage;
import ch.urinet.archiv.abnahme.Pruefliste.UngueltigePruefliste;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schema der Chat-Prüfliste (Spec 02, Aufgabe 9.1, Req 13.1) mit einem synthetischen Beispiel. Die echte Prüfliste
 * liegt nie im Repository; Fehlermeldungen des Laders nennen darum nie einen Wert.
 */
class PrueflisteSchemaTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final String GEHEIM = "GEHEIMER-TESTWERT-4711";

    @Test
    void beispielIstGueltig() {
        List<Frage> fragen = Pruefliste.aus(beispiel());

        assertThat(fragen).hasSize(20);
        assertThat(fragen).filteredOn(Frage::beispielfrage).extracting(Frage::nr).containsExactly(1, 2, 3, 4, 5, 6, 7);
        Frage zwei = fragen.get(1);
        assertThat(zwei.kernwerte()).containsExactly("Testfirma 2", "CHF 2.00");
        assertThat(zwei.quellen()).hasSize(2);
        assertThat(zwei.quellen().get(0).datenstand()).isEqualTo("kennzahl");
        assertThat(zwei.quellen().get(1).dokument()).isNotNull();
        assertThat(fragen.get(2).quellen()).isEmpty();
        assertThat(fragen.get(2).hinweis()).isNotBlank();
        assertThat(fragen.get(3).hinweis()).isEmpty();
    }

    @Test
    void lesenAusDateiPruefenAlsYaml(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("chat-pruefliste.yaml");
        try (InputStream in = Pruefliste.class.getResourceAsStream("/abnahme/chat-pruefliste.beispiel.yaml")) {
            Files.write(datei, in.readAllBytes());
        }
        assertThat(Pruefliste.lesen(datei)).hasSize(20);

        Files.writeString(datei, "- [unvollstaendig: {");
        assertThatThrownBy(() -> Pruefliste.lesen(datei)).isInstanceOf(UngueltigePruefliste.class);
    }

    @Test
    void schemaVerletzungenWerdenAbgelehnt() {
        abgelehnt("Kernwerte fehlen", f -> ((ObjectNode) f.get(0).get("erwartet")).remove("kernwerte"), "/0/erwartet (required)");
        abgelehnt("leere Kernwerte", f -> ((ObjectNode) f.get(0).get("erwartet")).putArray("kernwerte"), "/0/erwartet/kernwerte (minItems)");
        abgelehnt("Quellen fehlen", f -> ((ObjectNode) f.get(0).get("erwartet")).remove("quellen"), "/0/erwartet (required)");
        abgelehnt("unbekanntes Feld", f -> ((ObjectNode) f.get(0)).put("antwort", "x"), "/0 (additionalProperties)");
        abgelehnt("Nummer als Text", f -> ((ObjectNode) f.get(0)).put("nr", GEHEIM), "/0/nr (type)");
        abgelehnt("Frage fehlt", f -> ((ObjectNode) f.get(0)).remove("frage"), "/0 (required)");
        abgelehnt("Frage zu lang", f -> ((ObjectNode) f.get(0)).put("frage", "x".repeat(2001)), "/0/frage (maxLength)");
        abgelehnt("Dokument-ID keine UUID", f -> quelle(f).put("dokument", GEHEIM), "(format)");
        abgelehnt("Quelle mit beiden Arten", f -> quelle(f).put("datenstand", "kennzahl"), "(oneOf)");
        abgelehnt("Datenstand mit Grossbuchstaben", f -> {
            ObjectNode q = quelle(f);
            q.remove("dokument");
            q.put("datenstand", GEHEIM);
        }, "(oneOf)");
        assertThatThrownBy(() -> Pruefliste.aus(YAML.createObjectNode())).as("Wurzel kein Array")
                .isInstanceOf(UngueltigePruefliste.class).hasMessageContaining("type");
    }

    @Test
    void regelnDerListeWerdenGeprueft() {
        abgelehnt("19 Fragen", f -> f.remove(19), null);
        abgelehnt("doppelte Nummer", f -> ((ObjectNode) f.get(8)).put("nr", 10), null);
        abgelehnt("Beispielfrage ausserhalb 1 bis 7", f -> ((ObjectNode) f.get(10)).put("beispielfrage", true), null);
        abgelehnt("Nummer 3 keine Beispielfrage", f -> ((ObjectNode) f.get(2)).put("beispielfrage", false), null);
    }

    @Test
    void meldungenNennenNieEinenWert() {
        ArrayNode liste = beispiel();
        ((ObjectNode) liste.get(0)).put("nr", GEHEIM);
        ((ObjectNode) liste.get(1).get("erwartet").get("quellen").get(1)).put("dokument", GEHEIM);

        assertThatThrownBy(() -> Pruefliste.aus(liste)).isInstanceOf(UngueltigePruefliste.class)
                .hasMessageContaining("Schema verletzt an").hasMessageNotContaining(GEHEIM)
                .hasMessageNotContaining("Beispielfrage");
    }

    private static void abgelehnt(String fall, Consumer<ArrayNode> aendern, String regel) {
        ArrayNode liste = beispiel();
        aendern.accept(liste);
        var gepruft = assertThatThrownBy(() -> Pruefliste.aus(liste)).as(fall).isInstanceOf(UngueltigePruefliste.class);
        if (regel != null) {
            gepruft.hasMessageContaining(regel);
        }
    }

    /** Die Dokument-Quelle der zweiten Frage (ihre zweite Quelle). */
    private static ObjectNode quelle(ArrayNode liste) {
        return (ObjectNode) liste.get(1).get("erwartet").get("quellen").get(1);
    }

    private static ArrayNode beispiel() {
        try (InputStream in = Pruefliste.class.getResourceAsStream("/abnahme/chat-pruefliste.beispiel.yaml")) {
            JsonNode baum = YAML.readTree(in);
            return (ArrayNode) baum;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
