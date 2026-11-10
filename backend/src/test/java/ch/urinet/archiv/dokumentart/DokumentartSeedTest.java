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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 9.2: Der Seed von {@code urinet} stimmt Feld für Feld mit der Stammdaten-Tabelle in design.md überein. */
class DokumentartSeedTest {

    static final String SEED_URINET = "seed/urinet/dokumentarten.json";

    static List<StammdatenTabelle.Art> seedUrinet() throws IOException {
        try (InputStream inhalt = DokumentartSeedTest.class.getClassLoader().getResourceAsStream(SEED_URINET)) {
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
    void unbekannteZelleIstRot() {
        assertThat(tabelle("| `OFFERTE` | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | nein | – |"))
                .extracting(StammdatenTabelle.Art::beschreibung).containsExactly((String) null);
        assertThatThrownBy(() -> tabelle("| `OFFERTE` | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | vielleicht | x |"))
                .hasMessageContaining("vielleicht");
        assertThatThrownBy(() -> tabelle("| OFFERTE | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | ja | x |"))
                .hasMessageContaining("Code");
        assertThatThrownBy(() -> tabelle("| `OFFERTE` | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | ja |"))
                .hasMessageContaining("Spalten");
        assertThatThrownBy(() -> StammdatenTabelle.tabelle(List.of(StammdatenTabelle.START,
                "| Code | Name | Gruppen | Pflichtfelder | Vertraulichkeit | Jahre | ab | Pflicht |",
                "| --- | --- | --- | --- | --- | --- | --- | --- |", StammdatenTabelle.ENDE)))
                .hasMessageContaining("Kopf");
    }

    @Test
    void seedFeldOhneSpalteIstRot() throws IOException {
        var json = new ObjectMapper();
        assertThat(StammdatenTabelle.ausSeed(json.readTree("""
                {"_quelle": "x", "dokumentarten": [{"code": "A", "_notiz": "x", "zusatz_schema": {},
                 "aufbewahrung_jahre": null, "gesetzliche_pflicht": false}]}""")))
                .extracting(StammdatenTabelle.Art::code).containsExactly("A");
        assertThatThrownBy(() -> StammdatenTabelle.ausSeed(json.readTree("""
                {"dokumentarten": [{"code": "A", "farbe": "rot"}]}"""))).hasMessageContaining("farbe");
    }

    /** So streng wie {@code KlassenpfadDokumentartSeed} (Review PR #38). */
    @Test
    void seedMitFehlenderOderFalschTypisierterRegelIstRot() throws IOException {
        var json = new ObjectMapper();
        for (String art : List.of(
                "{\"code\": \"A\", \"aufbewahrung_jahre\": 10}",
                "{\"code\": \"A\", \"aufbewahrung_jahre\": 10, \"gesetzliche_pflicht\": \"ja\"}",
                "{\"code\": \"A\", \"gesetzliche_pflicht\": true}",
                "{\"code\": \"A\", \"aufbewahrung_jahre\": \"10\", \"gesetzliche_pflicht\": true}",
                "{\"code\": \"A\", \"aufbewahrung_jahre\": 10.5, \"gesetzliche_pflicht\": true}",
                "{\"code\": \"A\", \"aufbewahrung_jahre\": 10, \"gesetzliche_pflicht\": true, \"beschreibung\": \" \"}",
                "{\"code\": \"A\", \"aufbewahrung_jahre\": 10, \"gesetzliche_pflicht\": true, \"beschreibung\": 1}")) {
            var seed = json.readTree("{\"dokumentarten\": [" + art + "]}");
            assertThatThrownBy(() -> StammdatenTabelle.ausSeed(seed)).as(art).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Wie {@code KlassenpfadDokumentartSeed}: ohne {@code aktiv} ist die Art aktiv (Review PR #40). */
    @Test
    void seedOhneAktivIstAktiv() throws IOException {
        var seed = new ObjectMapper().readTree("""
                {"dokumentarten": [{"code": "A", "aufbewahrung_jahre": null, "gesetzliche_pflicht": false}]}""");
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
        try (InputStream inhalt = DokumentartSeedTest.class.getClassLoader().getResourceAsStream(SEED_URINET)) {
            seed = new ObjectMapper().readTree(inhalt);
        }
        String schema = null;
        for (JsonNode art : seed.path("dokumentarten")) {
            if ("LOHNABRECHNUNG".equals(art.path("code").asText())) {
                schema = art.path("zusatz_schema").toString();
            }
        }
        assertThat(schema).isNotNull();
        var pruefer = new NetworkntZusatzSchemaPruefer(Duration.ofMillis(50));
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
}
