package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegelPruefungTest {

    @Test
    void pflichtWennLehntBeiFehlerSchwereAbUndWarntBeiUnveraendertemBestand() {
        Regel regel = new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                "FEHLER", "Belegdatum fehlt.", true);
        RegelPruefung pruefung = pruefung(List.of(regel), (art, alle, felder, werte, id) -> false);
        Map<String, Object> werte = Map.of("belegnummer", "R1");

        assertThatThrownBy(() -> pruefung.pruefen("RECHNUNG", werte, null, UUID.randomUUID()))
                .isInstanceOfSatisfying(RegelVerletzt.class, fehler -> {
                    assertThat(fehler.code()).isEqualTo(Fehlercode.REGEL_VERLETZT);
                    assertThat(fehler.regeln()).containsExactly(
                            new RegelVerletzt.Befund("datum_pflicht", "Belegdatum fehlt."));
                });
        assertThat(pruefung.pruefen("RECHNUNG", werte, werte, UUID.randomUUID()))
                .containsExactly(new RegelPruefung.Warnung("datum_pflicht", "Belegdatum fehlt."));
    }

    @Test
    void eindeutigIgnoriertLeereFelderUndLehntGefundeneDubletteAb() {
        Regel regel = new Regel("einmalig", "RECHNUNG", "EINDEUTIG",
                Map.of("felder", List.of("belegnummer")), "FEHLER", "Nummer bereits erfasst.", true);
        RegelPruefung pruefung = pruefung(List.of(regel), (art, alle, felder, werte, id) -> true);

        assertThat(pruefung.pruefen("RECHNUNG", Map.of("belegnummer", ""), null, null)).isEmpty();
        assertThatThrownBy(() -> pruefung.pruefen("RECHNUNG", Map.of("belegnummer", "R1"), null, null))
                .isInstanceOf(RegelVerletzt.class);
    }

    @Test
    void artwechselGiltAlsAenderungDerRegelfelder() {
        Regel regel = new Regel("einmalig", "RECHNUNG", "EINDEUTIG",
                Map.of("felder", List.of("belegnummer")), "FEHLER", "Nummer bereits erfasst.", true);
        RegelPruefung pruefung = pruefung(List.of(regel), (art, alle, felder, werte, id) -> true);
        Map<String, Object> vorher = Map.of("art", "QUITTUNG", "belegnummer", "R1");
        Map<String, Object> nachher = Map.of("art", "RECHNUNG", "belegnummer", "R1");

        assertThatThrownBy(() -> pruefung.pruefen("RECHNUNG", nachher, vorher, UUID.randomUUID()))
                .isInstanceOf(RegelVerletzt.class);
        assertThat(pruefung.pruefen("RECHNUNG", nachher, nachher, UUID.randomUUID()))
                .containsExactly(new RegelPruefung.Warnung("einmalig", "Nummer bereits erfasst."));
    }

    @Test
    void mehrfachwerteWerdenSortiertUndOhneDuplikateVerglichen() {
        Regel regel = new Regel("einmalig", "RECHNUNG", "EINDEUTIG",
                Map.of("felder", List.of("klassifikation.fachgebiet", "tags")), "FEHLER", "Doppelt.", true);
        List<List<Object>> gepruefteWerte = new java.util.ArrayList<>();
        RegelPruefung pruefung = pruefung(List.of(regel), (art, alle, felder, werte, id) -> {
            gepruefteWerte.add(werte);
            return false;
        });
        Map<String, Object> vorher = Map.of("art", "RECHNUNG", "klassifikation.fachgebiet", List.of("a", "b"),
                "tags", List.of("x"));
        Map<String, Object> nachher = Map.of("art", "RECHNUNG", "klassifikation.fachgebiet",
                List.of("b", "a", "b"), "tags", List.of("x"));

        assertThat(pruefung.pruefen("RECHNUNG", nachher, vorher, UUID.randomUUID())).isEmpty();
        assertThat(gepruefteWerte).containsExactly(List.of(List.of("a", "b"), List.of("x")));

        RegelPruefung verletzt = pruefung(List.of(regel), (art, alle, felder, werte, id) -> true);
        assertThat(verletzt.pruefen("RECHNUNG", nachher, vorher, UUID.randomUUID()))
                .as("gleiche Menge in anderer Reihenfolge ist keine Änderung")
                .containsExactly(new RegelPruefung.Warnung("einmalig", "Doppelt."));
    }

    private static RegelPruefung pruefung(List<Regel> regeln, RegelDatenPort daten) {
        Konfiguration abbild = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null),
                List.of(), List.of(), regeln);
        KonfigurationVerwaltung konfiguration = new KonfigurationVerwaltung(null, null, null, null, null, null,
                null, null, null, null) {
            @Override
            public Konfiguration aktuell() {
                return abbild;
            }
        };
        return new RegelPruefung(konfiguration, daten, new ZusatzSchemaPruefer() {
            @Override
            public Set<String> schemaPruefen(String schemaJson) {
                return Set.of();
            }

            @Override
            public Map<String, String> feldTypen(String schemaJson) {
                return Map.of();
            }

            @Override
            public Set<String> seedSchemaPruefen(String schemaJson) {
                return Set.of();
            }

            @Override
            public void wertePruefen(String schemaJson, String werteJson) {
            }

            @Override
            public Map<String, Object> werteLesen(String werteJson) {
                return Map.of();
            }

            @Override
            public boolean reservierteFelderGueltig(String schemaJson) {
                return true;
            }

            @Override
            public boolean datumsfeldGueltig(String schemaJson, String feld) {
                return false;
            }

            @Override
            public LocalDate datumswert(String werteJson, String feld) {
                return null;
            }
        });
    }
}
