package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Datensatz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Beide Formate von erwartet.json und der Teil-Filter (Req 0.8, 0.10). Nur erfundene Werte. */
class ErwartungLeserTest {

    @TempDir
    Path tmp;

    private Path synthetisch() throws IOException {
        Path datei = tmp.resolve("synthetisch/erwartet.json");
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, """
                {"version": 1, "seed": 1,
                 "mandant": {"name": "Muster Mandant", "uid": null, "ibans": ["CH9300762011623852957"]},
                 "belege": [
                  {"datei": "belege/01_quittung_a.pdf", "art": "QUITTUNG", "kategorie": "QUITTUNG", "datum": "2026-03-01",
                   "partner": "Laden", "betrag": "12.50", "waehrung": "CHF", "belegnummer": null, "richtung": "AUSGABE",
                   "faelligkeit": null, "foto": false, "qr": null, "teil": "abstimmen", "randfaelle": ["x"], "seed": 1},
                  {"datei": "belege/02_quittung_b.pdf", "art": "QUITTUNG", "kategorie": "QUITTUNG", "datum": null,
                   "partner": "Laden", "betrag": null, "waehrung": "CHF", "belegnummer": "7", "richtung": null,
                   "faelligkeit": null, "foto": false, "qr": null, "teil": "zurueckgehalten", "randfaelle": [], "seed": 1}
                 ]}
                """);
        return datei;
    }

    @Test
    void synthetischesFormat() throws IOException {
        Datensatz d = ErwartungLeser.synthetisch(synthetisch());
        assertThat(d.mandant().ibans()).containsExactly("CH9300762011623852957");
        Erwartung a = d.belege().getFirst();
        assertThat(a.id()).isEqualTo("01_quittung_a.pdf");
        assertThat(a.datei()).isEqualTo(tmp.resolve("synthetisch/belege/01_quittung_a.pdf"));
        assertThat(a.datum()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(a.betrag()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(a.belegnummer()).isNull();
        assertThat(d.belege().get(1).betrag()).isNull();
    }

    @Test
    void ohneMandantKopfAbbruch() throws IOException {
        Path datei = tmp.resolve("alt.json");
        Files.writeString(datei, "{\"version\": 1, \"belege\": []}");
        assertThatThrownBy(() -> ErwartungLeser.synthetisch(datei)).hasMessageContaining("mandant");
    }

    @Test
    void echtesFormatNurBestaetigteOhnePruefmarke() throws IOException {
        Path erwartet = tmp.resolve("erwartet.json");
        Files.writeString(erwartet, """
                {"_hinweis": "test", "belege": [
                  {"datei": "a.pdf", "art": "QUITTUNG", "kategorie": "QUITTUNG", "datum": "2026-01-02", "partner": "P",
                   "betrag": "1.00", "waehrung": "CHF", "belegnummer": null, "richtung": "AUSGABE", "faelligkeit": null,
                   "foto": false, "teil": null, "bestaetigt": true},
                  {"datei": "b.pdf", "art": "QUITTUNG", "kategorie": "QUITTUNG", "datum": "2026-01-02", "partner": "P",
                   "betrag": "1.00", "waehrung": "CHF", "belegnummer": null, "richtung": "AUSGABE", "faelligkeit": null,
                   "foto": false, "teil": null, "bestaetigt": false},
                  {"datei": "c.pdf", "art": "QUITTUNG", "kategorie": "QUITTUNG",
                   "datum": {"_pruefen": true, "a": "2026-01-02", "b": "2026-02-01"}, "partner": "P",
                   "betrag": "1.00", "waehrung": "CHF", "belegnummer": null, "richtung": "AUSGABE", "faelligkeit": null,
                   "foto": false, "teil": null, "bestaetigt": true}
                ]}
                """);
        Path kennungen = tmp.resolve("mandant-kennungen.json");
        Files.writeString(kennungen, "{\"_hinweis\": \"t\", \"uid\": \"CHE-123.456.788\", \"ibans\": [\"CH56 0483 5012 3456 7800 9\"]}");
        Datensatz d = ErwartungLeser.echt(erwartet, kennungen, "Muster Mandant");
        assertThat(d.belege()).hasSize(1);
        assertThat(d.ausgeschlossen()).isEqualTo(2);
        Erwartung e = d.belege().getFirst();
        assertThat(e.echt()).isTrue();
        assertThat(e.id()).hasSize(8).isEqualTo(ErwartungLeser.kurzId("a.pdf")).doesNotContain("a.pdf");
        assertThat(e.datei()).isEqualTo(tmp.resolve("belege/a.pdf"));
        assertThat(d.mandant().uid()).isEqualTo("CHE-123.456.788");
    }

    @Test
    void teilFilterOhneGateNieZurueckgehalten() throws IOException {
        List<Erwartung> alle = ErwartungLeser.synthetisch(synthetisch()).belege();
        assertThat(Modus.aus(List.of())).isEqualTo(Modus.ABSTIMMEN);
        assertThat(Modus.ABSTIMMEN.waehle(alle)).extracting(Erwartung::teil).containsOnly("abstimmen");
        assertThat(Modus.GATE.waehle(alle)).extracting(Erwartung::teil).containsOnly("zurueckgehalten");
        assertThat(Modus.REALITAET.waehle(alle)).isEmpty();
        assertThat(Modus.aus(List.of("--limit", "3"))).isEqualTo(Modus.ABSTIMMEN);
        assertThat(Modus.aus(List.of("--gate"))).isEqualTo(Modus.GATE);
        assertThatThrownBy(() -> Modus.aus(List.of("--gate", "--realitaet"))).isInstanceOf(IllegalArgumentException.class);
    }
}
