package ch.urinet.archiv.dokumentart;

import ch.urinet.archiv.dokumentart.adapter.out.schema.NetworkntZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 9.2: Der Seed von {@code urinet} stimmt Feld für Feld mit der Stammdaten-Tabelle in design.md überein. */
class KonfigurationSeedTest {

    static final String SEED_URINET = "seed/urinet/konfiguration.json";

    static List<StammdatenTabelle.Art> seedUrinet() throws IOException {
        try (InputStream inhalt = KonfigurationSeedTest.class.getClassLoader().getResourceAsStream(SEED_URINET)) {
            return StammdatenTabelle.ausSeed(new ObjectMapper().readTree(inhalt));
        }
    }

    @Test
    void seedEntsprichtDerTabelle() throws IOException {
        List<StammdatenTabelle.Art> tabelle = StammdatenTabelle.ausDesign();

        assertThat(tabelle).hasSize(11);
        assertThat(seedUrinet()).containsExactlyElementsOf(tabelle);
    }

    @Test
    void seedEnthaeltDieUrinetBelegnummerRegeln() throws IOException {
        JsonNode seed;
        try (InputStream inhalt = KonfigurationSeedTest.class.getClassLoader().getResourceAsStream(SEED_URINET)) {
            seed = new ObjectMapper().readTree(inhalt);
        }
        Map<String, JsonNode> arten = new java.util.HashMap<>();
        seed.path("arten").forEach(art -> arten.put(art.path("code").asText(), art));

        assertThat(arten.get("AUSGANGSRECHNUNG").path("selbstAusgestellt").asBoolean()).isTrue();
        assertThat(texte(arten.get("AUSGANGSRECHNUNG").path("belegnummerMuster")))
                .containsExactly("^(?<jahr>\\d{4})-(?<nr>\\d{3})$", "^R(?<nr>\\d{4})$");
        assertThat(arten.get("AUSGANGSRECHNUNG").path("fremdbelegArt").asText()).isEqualTo("LIEFERANTENBELEG");
        assertThat(arten.get("OFFERTE").path("selbstAusgestellt").asBoolean()).isTrue();
        assertThat(texte(arten.get("OFFERTE").path("belegnummerMuster"))).containsExactly("^O-\\d{4}-\\d{3}$");
        assertThat(arten.get("OFFERTE").path("fremdbelegArt").asText()).isEqualTo("LIEFERANTENBELEG");
        assertThat(texte(arten.get("LIEFERANTENBELEG").path("belegnummerMuster"))).containsExactly("^F\\d{4}$");
        assertThat(arten.get("LIEFERANTENBELEG").path("ohneZahlungsdatenArt").asText()).isEqualTo("QUITTUNG");
        assertThat(arten.get("LOHNABRECHNUNG").path("selbstAusgestellt").asBoolean()).isTrue();
    }

    @Test
    void unbekannteZelleIstRot() {
        assertThat(tabelle(basisZeile()))
                .extracting(StammdatenTabelle.Art::beschreibung).containsExactly((String) null);
        assertThatThrownBy(() -> tabelle(basisZeile().replace("| ja |", "| vielleicht |")))
                .hasMessageContaining("vielleicht");
        assertThatThrownBy(() -> tabelle(basisZeile().replace("`OFFERTE`", "OFFERTE")))
                .hasMessageContaining("Code");
        assertThatThrownBy(() -> tabelle(basisZeile().replace(" | – |", " |")))
                .hasMessageContaining("Spalten");
        assertThatThrownBy(() -> StammdatenTabelle.tabelle(List.of(StammdatenTabelle.START,
                "| Code | Name |", "| --- | --- |", StammdatenTabelle.ENDE)))
                .hasMessageContaining("Kopf");
    }

    @Test
    void seedFeldOhneSpalteIstRot() throws IOException {
        var json = new ObjectMapper();
        assertThat(StammdatenTabelle.ausSeed(json.readTree("""
                {"_quelle": "x", "format": 1, "einstellungen": {}, "kiTexte": {}, "klassifikationen": [],
                 "arten": [{"code": "A", "_notiz": "x", "zusatzSchema": {},
                 "aufbewahrung": {"jahre": null}, "gesetzlichePflicht": false}], "regeln": []}""")))
                .extracting(StammdatenTabelle.Art::code).containsExactly("A");
        assertThatThrownBy(() -> StammdatenTabelle.ausSeed(json.readTree("""
                {"arten": [{"code": "A", "farbe": "rot"}]}"""))).hasMessageContaining("farbe");
    }

    /** So streng wie {@code KlassenpfadDokumentartSeed} (Review PR #38). */
    @Test
    void seedMitFehlenderOderFalschTypisierterRegelIstRot() throws IOException {
        var json = new ObjectMapper();
        for (String art : List.of(
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": 10}}",
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": 10}, \"gesetzlichePflicht\": \"ja\"}",
                "{\"code\": \"A\", \"gesetzlichePflicht\": true}",
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": \"10\"}, \"gesetzlichePflicht\": true}",
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": 10.5}, \"gesetzlichePflicht\": true}",
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": 10}, \"gesetzlichePflicht\": true, \"beschreibung\": \" \"}",
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": 10}, \"gesetzlichePflicht\": true, \"beschreibung\": 1}")) {
            var seed = json.readTree(configMitArt(art));
            assertThatThrownBy(() -> StammdatenTabelle.ausSeed(seed)).as(art).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Wie {@code KlassenpfadDokumentartSeed}: ohne {@code aktiv} ist die Art aktiv (Review PR #40). */
    @Test
    void seedOhneAktivIstAktiv() throws IOException {
        var seed = new ObjectMapper().readTree(configMitArt(
                "{\"code\": \"A\", \"aufbewahrung\": {\"jahre\": null}, \"gesetzlichePflicht\": false}"));
        assertThat(StammdatenTabelle.ausSeed(seed)).extracting(StammdatenTabelle.Art::aktiv).containsExactly(true);
    }

    /**
     * F7 (entschieden von Fabian am 10.11.2026): {@code zusatz.periode} der LOHNABRECHNUNG nimmt {@code JJJJ}
     * (Lohnausweis) und {@code JJJJ-MM} an (Review PR #40 Nacht, S3).
     */
    @ParameterizedTest
    @CsvSource({"2026, true", "2026-03, true", "2026-12, true", "2026-13, false", "2026-00, false", "2026-3, false",
            "26, false", "20261, false", "2026-, false"})
    void periodeDerLohnabrechnungNimmtJahrUndMonatAn(String periode, boolean gueltig) throws IOException {
        JsonNode seed;
        try (InputStream inhalt = KonfigurationSeedTest.class.getClassLoader().getResourceAsStream(SEED_URINET)) {
            seed = new ObjectMapper().readTree(inhalt);
        }
        String schema = null;
        for (JsonNode art : seed.path("arten")) {
            if ("LOHNABRECHNUNG".equals(art.path("code").asText())) {
                schema = art.path("zusatzSchema").toString();
            }
        }
        assertThat(schema).isNotNull();
        // Grosszügige Frist: geprüft wird das Schema, nicht die Zeitgrenze (sonst abhängig vom Tempo des Rechners)
        var pruefer = new NetworkntZusatzSchemaPruefer(Duration.ofSeconds(10), Duration.ofSeconds(10));
        String werte = "{\"periode\":\"" + periode + "\",\"person\":\"Testperson\"}";
        if (gueltig) {
            pruefer.wertePruefen(schema, werte);
        } else {
            String s = schema;
            assertThatThrownBy(() -> pruefer.wertePruefen(s, werte)).isInstanceOfSatisfying(FachFehler.class,
                    f -> assertThat(f.code()).isEqualTo(Fehlercode.ZUSATZ_UNGUELTIG));
        }
    }

    private static List<StammdatenTabelle.Art> tabelle(String zeile) {
        List<String> kopf = StammdatenTabelle.kopfzeilen();
        return StammdatenTabelle.tabelle(List.of(StammdatenTabelle.START, kopf.get(0), kopf.get(1), zeile,
                StammdatenTabelle.ENDE));
    }

    private static String basisZeile() {
        return "| `OFFERTE` | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | – | nein | – "
                + "| ja | – | – | – | ja | – | 0 | – |";
    }

    private static String configMitArt(String art) {
        return "{\"format\":1,\"einstellungen\":{},\"kiTexte\":{},\"klassifikationen\":[],\"arten\":["
                + art + "],\"regeln\":[]}";
    }

    private static List<String> texte(JsonNode liste) {
        List<String> ergebnis = new java.util.ArrayList<>();
        liste.forEach(wert -> ergebnis.add(wert.asText()));
        return ergebnis;
    }
}
