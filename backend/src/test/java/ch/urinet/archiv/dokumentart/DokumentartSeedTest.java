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

    private static final List<String> KOPF = List.of(
            "<!-- seed:dokumentarten:start -->",
            "| Code | Name | Gruppen | Pflichtfelder | Vertraulichkeit | Jahre | ab | Pflicht |",
            "| --- | --- | --- | --- | --- | --- | --- | --- |");

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
        assertThatThrownBy(() -> tabelle("| `OFFERTE` | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | vielleicht |"))
                .hasMessageContaining("vielleicht");
        assertThatThrownBy(() -> tabelle("| OFFERTE | Offerte | finanz | titel | NORMAL | 10 | GESCHAEFTSJAHR | ja |"))
                .hasMessageContaining("Code");
        assertThatThrownBy(() -> tabelle("| `OFFERTE` | Offerte | finanz | titel | NORMAL | 10 | ja |"))
                .hasMessageContaining("Spalten");
    }

    @Test
    void seedFeldOhneSpalteIstRot() throws IOException {
        var json = new ObjectMapper();
        assertThat(StammdatenTabelle.ausSeed(json.readTree("""
                {"_quelle": "x", "dokumentarten": [{"code": "A", "_notiz": "x", "zusatz_schema": {}}]}""")))
                .extracting(StammdatenTabelle.Art::code).containsExactly("A");
        assertThatThrownBy(() -> StammdatenTabelle.ausSeed(json.readTree("""
                {"dokumentarten": [{"code": "A", "farbe": "rot"}]}"""))).hasMessageContaining("farbe");
    }

    private static List<StammdatenTabelle.Art> tabelle(String zeile) {
        return StammdatenTabelle.tabelle(List.of(KOPF.get(0), KOPF.get(1), KOPF.get(2), zeile,
                "<!-- seed:dokumentarten:ende -->"));
    }
}
