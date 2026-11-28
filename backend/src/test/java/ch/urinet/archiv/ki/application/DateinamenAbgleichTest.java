package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.konfiguration.application.DateinamenTreffer;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Vorschlag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DateinamenAbgleichTest {

    @Test
    void dateinameUeberschreibtErkannteFelderUndMarkiertWiderspruchZurOcr() {
        Vorschlag ki = new Vorschlag("QUITTUNG", "Titel", null, "alter Partner", null, null, null, "falsche",
                null, null, null, null, null, "NORMAL", List.of(), Map.of(), false, null);
        DateinamenTreffer dateiname = new DateinamenTreffer(3, "AUSGANGSRECHNUNG",
                Map.of("belegnummer", "2026-003", "partner", "Muster Büro AG", "periode", "2026-03"));

        Vorschlag ergebnis = DateinamenAbgleich.anwenden(Optional.of(ki), Optional.of(dateiname),
                "Der Volltext enthält eine andere Nummer.").orElseThrow();

        assertThat(ergebnis.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(ergebnis.belegnummer()).isEqualTo("2026-003");
        assertThat(ergebnis.partnerName()).isEqualTo("Muster Büro AG");
        assertThat(ergebnis.dynamischeWerte()).containsEntry("periode", "2026-03");
        assertThat(ergebnis.sicherheit(Feld.BELEGNUMMER)).isEqualTo(java.math.BigDecimal.ONE);
        assertThat(ergebnis.sicherheit(Feld.PARTNER)).isEqualTo(java.math.BigDecimal.ONE);
        assertThat(ergebnis.sicherheit(Feld.ART)).isEqualTo(java.math.BigDecimal.ONE);
        assertThat(ergebnis.dynamischeSicherheit()).containsEntry("periode", java.math.BigDecimal.ONE);
        assertThat(ergebnis.herkunft()).containsEntry("belegnummer", "DATEINAME")
                .containsEntry(Feld.PARTNER.schluessel(), "DATEINAME").containsEntry("periode", "DATEINAME")
                .containsEntry("art", "DATEINAME");
        assertThat(ergebnis.bittePruefen()).containsExactly("belegnummer");
    }

    @Test
    void gleicheBelegnummerImVolltextWirdNichtZumPruefenMarkiert() {
        Vorschlag ergebnis = DateinamenAbgleich.anwenden(Optional.empty(),
                Optional.of(new DateinamenTreffer(1, "AUSGANGSRECHNUNG", Map.of("belegnummer", "2026-003"))),
                "Rechnung 2026-003").orElseThrow();

        assertThat(ergebnis.bittePruefen()).isEmpty();
        assertThat(ergebnis.herkunft()).containsEntry("art", "DATEINAME");
    }
}
