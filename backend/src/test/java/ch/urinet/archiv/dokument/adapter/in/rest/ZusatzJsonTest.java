package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZusatzJsonTest {

    private static final JsonNodeFactory KNOTEN = JsonNodeFactory.instance;

    @Test
    void nichtEndlicheZahlWirdNieZuText() {
        for (double wert : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            ObjectNode tief = KNOTEN.objectNode();
            tief.putArray("liste").add(1).addObject().set("x", DoubleNode.valueOf(wert));
            for (JsonNode zusatz : new JsonNode[] {KNOTEN.objectNode().set("x", DoubleNode.valueOf(wert)), tief}) {
                assertThatThrownBy(() -> ZusatzJson.text(zusatz)).isInstanceOf(FachFehler.class)
                        .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.ZUSATZ_UNGUELTIG);
            }
        }
    }

    @Test
    void endlicheZahlenUndTexteBleibenUnveraendert() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode zusatz = json.readTree("{\"x\": 1e300, \"y\": [0.5, -2], \"z\": \"Infinity\"}");
        assertThat(json.readTree(ZusatzJson.text(zusatz))).isEqualTo(zusatz);
    }
}
