package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DateinamenErkennungTest {

    @Test
    void erstePassendeRegelErkenntFelderOhneGrossKleinschreibungUndNormalisiertPartner() {
        var art = new Konfiguration.Art("AUSGANGSRECHNUNG", "Rechnung", null, List.of("finanz"), List.of("titel"),
                Konfiguration.OHNE_ZUSATZ, "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true,
                false, List.of(), null, null, true, List.of(), null, 0);
        var regel1 = new Konfiguration.DateinamenRegel(1, "^kein-treffer$", art.code(),
                Map.of("belegnummer", "nr"));
        var regel2 = new Konfiguration.DateinamenRegel(2,
                "^gen-(?<nr>\\d{4}-\\d{3})_(?<partner>.+)\\.pdf$", art.code(),
                Map.of("belegnummer", "nr", "partner", "partner"));
        Konfiguration konfiguration = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(), Konfiguration.KiTexte.LEER, List.of(regel1, regel2));
        DateinamenErkennung erkennung = new DateinamenErkennung(() -> konfiguration, Duration.ofMillis(100));

        var treffer = erkennung.erkennen("GEN-2026-003_Muster_Büro-AG.PDF");

        assertThat(treffer).isPresent();
        assertThat(treffer.orElseThrow().regelNr()).isEqualTo(2);
        assertThat(treffer.orElseThrow().felder()).containsExactlyInAnyOrderEntriesOf(
                Map.of("belegnummer", "2026-003", "partner", "Muster Büro AG"));
        assertThat(treffer.orElseThrow().alsMap()).containsEntry("herkunft", "DATEINAME");
    }

    @Test
    void keinePassendeRegelIstKeinTreffer() {
        DateinamenErkennung erkennung = new DateinamenErkennung(
                () -> new Konfiguration(1,
                        new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                        List.of()), Duration.ofMillis(100));

        assertThat(erkennung.erkennen("synthetisch.pdf")).isEmpty();
    }
}
