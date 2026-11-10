package ch.urinet.archiv.dokumentart;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
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

    private static List<StammdatenTabelle.Art> tabelle(String zeile) {
        List<String> kopf = StammdatenTabelle.kopfzeilen();
        return StammdatenTabelle.tabelle(List.of(StammdatenTabelle.START, kopf.get(0), kopf.get(1), zeile,
                StammdatenTabelle.ENDE));
    }
}
