package ch.urinet.archiv.dokumentart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VhfGskDokumentartSeedTest {

    @Test
    void seedEnthaeltVerbandsartenOhneFinanzartenUndMitReserviertenFeldern() throws IOException {
        JsonNode seed;
        try (var inhalt = Files.newInputStream(Path.of("src/main/resources/seed/vhf-gsk/konfiguration.json"))) {
            seed = new ObjectMapper().readTree(inhalt);
        }

        JsonNode arten = seed.path("arten");
        JsonNode hinweise = new ObjectMapper().readTree(seed.path("_hinweise").asText());
        assertThat(arten).hasSize(8);
        assertThat(arten).extracting(art -> art.path("code").asText())
                .containsExactlyInAnyOrder("BULLETIN", "NORM_RICHTLINIE", "VORSTANDSPROTOKOLL", "MERKBLATT",
                        "FORMULAR", "SONSTIGES", "JAHRESBERICHT", "WISSENSQUELLE");

        assertThat(hinweise.path("verband").path("mehrfach").asBoolean()).isTrue();
        assertThat(hinweise.path("verband").path("suchfilter").asBoolean()).isTrue();
        assertThat(hinweise.path("verband").path("oeffentlich_filter").asBoolean()).isTrue();
        assertThat(hinweise.path("verband").path("ki_vorschlag").asBoolean()).isFalse();
        assertThat(hinweise.path("zugriff").path("nur_manuell").asBoolean()).isTrue();
        assertThat(hinweise.path("veroeffentlicht").path("ki_vorschlag").asBoolean()).isFalse();

        for (JsonNode art : arten) {
            List<String> gruppen = new ArrayList<>();
            art.path("gruppen").forEach(gruppe -> gruppen.add(gruppe.asText()));
            assertThat(gruppen).as(art.path("code").asText()).doesNotContain("finanz");
            assertThat(art.path("gesetzlichePflicht").asBoolean()).as(art.path("code").asText()).isFalse();
            JsonNode eigenschaften = art.path("zusatzSchema").path("properties");
            assertThat(eigenschaften.path("verband").path("type").asText()).isEqualTo("array");
            assertThat(texte(eigenschaften.path("verband").path("items").path("enum")))
                    .containsExactly("vhf", "gsk");
            // Anzeigetexte für die Oberfläche (x-labels); gespeichert wird weiter der Enum-Wert.
            JsonNode beschriftung = eigenschaften.path("verband").path("x-labels");
            assertThat(beschriftung.path("vhf").asText()).as(art.path("code").asText()).isEqualTo("VHF");
            assertThat(beschriftung.path("gsk").asText()).as(art.path("code").asText()).isEqualTo("GSK");
            assertThat(beschriftung).hasSize(2);
            assertThat(texte(eigenschaften.path("zugriff").path("enum")))
                    .containsExactly("oeffentlich", "mitglieder", "vorstand");
            assertThat(eigenschaften.path("zugriff").path("default").asText()).as(art.path("code").asText())
                    .isEqualTo("VORSTANDSPROTOKOLL".equals(art.path("code").asText()) ? "vorstand" : "mitglieder");
            assertThat(texte(art.path("zusatzSchema").path("required")))
                    .as(art.path("code").asText()).contains("verband", "zugriff", "veroeffentlicht");
            assertThat(eigenschaften.path("veroeffentlicht").path("default").asBoolean()).isFalse();
        }

        JsonNode bulletin = finde(arten, "BULLETIN").path("zusatzSchema").path("properties");
        assertThat(bulletin.has("reihe")).isTrue();
        assertThat(bulletin.has("ausgabe")).isTrue();
        assertThat(bulletin.has("jahr")).isTrue();
        assertThat(bulletin.has("fachgebiet")).isTrue();
        assertThat(finde(arten, "JAHRESBERICHT").path("zusatzSchema").path("properties").has("jahr")).isTrue();
        assertThat(finde(arten, "VORSTANDSPROTOKOLL").path("vertraulichkeit").asText()).isEqualTo("INTERN");
    }

    // Spec 04, Req 1.1: in vhf-gsk trägt WISSENSQUELLE zusätzlich die reservierten Felder zugriff und veroeffentlicht
    @Test
    void wissensquelleHaeltDieFestenEigenschaftenUndGleichtDemTestSeed() throws IOException {
        JsonNode art = finde(lesen("src/main/resources/seed/vhf-gsk/konfiguration.json").path("arten"),
                "WISSENSQUELLE");

        assertThat(texte(art.path("gruppen"))).containsExactly("frei");
        assertThat(texte(art.path("pflichtfelder"))).contains("titel", "datum");
        assertThat(art.path("aufbewahrung").path("jahre").isNull()).isTrue();
        assertThat(art.path("gesetzlichePflicht").asBoolean()).isFalse();
        assertThat(art.path("kiVorschlag").asBoolean()).isFalse();
        assertThat(art.path("zusatzSchema").path("properties").has("herausgeber")).isTrue();
        assertThat(art.path("zusatzSchema").path("additionalProperties").asBoolean(true)).isFalse();

        // Der Test-Seed überdeckt den echten auf dem Klassenpfad; die Art muss dort gleich lauten.
        JsonNode test = finde(lesen("src/test/resources/seed/vhf-gsk/konfiguration.json").path("arten"),
                "WISSENSQUELLE");
        assertThat(test).isEqualTo(art);
    }

    private static JsonNode lesen(String pfad) throws IOException {
        try (var inhalt = Files.newInputStream(Path.of(pfad))) {
            return new ObjectMapper().readTree(inhalt);
        }
    }

    private static List<String> texte(JsonNode werte) {
        List<String> ergebnis = new ArrayList<>();
        werte.forEach(wert -> ergebnis.add(wert.asText()));
        return ergebnis;
    }

    private static JsonNode finde(JsonNode arten, String code) {
        for (JsonNode art : arten) {
            if (code.equals(art.path("code").asText())) {
                return art;
            }
        }
        throw new AssertionError("Dokumentart fehlt: " + code);
    }
}
