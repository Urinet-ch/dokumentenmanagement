package ch.urinet.archiv.dokument.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DokumentVorschlagSicherheitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void dateinameWiderspruchBleibtTrotzSicherheitEinsAlsZuPruefenMarkiert() throws Exception {
        JsonNode vorschlag = JSON.readTree("""
                {
                  "art": "AUSGANGSRECHNUNG",
                  "belegnummer": "2026-003",
                  "herkunft": {"art": "DATEINAME", "belegnummer": "DATEINAME"},
                  "bittePruefen": ["belegnummer"]
                }
                """);
        JsonNode sicherheit = JSON.readTree("""
                {"art": 1.0, "belegnummer": 1.0}
                """);

        assertThat(DokumentResource.unsicher(vorschlag, sicherheit, new BigDecimal("0.5")))
                .containsExactly("belegnummer");
    }
}
