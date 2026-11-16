package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Vorschlag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadatenExtraktionTest {

    @Test
    void vhfVorschlagEnthaeltKeineFinanzdaten() {
        Vorschlag original = new Vorschlag("BULLETIN", "Ausgabe", LocalDate.of(2026, 1, 2), "Verein",
                "uid-1", "CH9300762011623852957", "verein@example.ch", "R-123", "AUSGABE",
                new BigDecimal("123.45"), "CHF", LocalDate.of(2026, 2, 1), "QR-123", "NORMAL",
                List.of("Mitteilung"), Map.of(
                        Feld.BELEGNUMMER, BigDecimal.ONE,
                        Feld.RICHTUNG, BigDecimal.ONE,
                        Feld.BETRAG, BigDecimal.ONE,
                        Feld.WAEHRUNG, BigDecimal.ONE,
                        Feld.FAELLIGKEIT, BigDecimal.ONE,
                        Feld.QR_REFERENZ, BigDecimal.ONE), false, null);

        Vorschlag bereinigt = MetadatenExtraktion.ohneFinanz(original);

        assertThat(bereinigt.partnerIban()).isNull();
        assertThat(bereinigt.belegnummer()).isNull();
        assertThat(bereinigt.richtung()).isNull();
        assertThat(bereinigt.betrag()).isNull();
        assertThat(bereinigt.waehrung()).isNull();
        assertThat(bereinigt.faelligkeit()).isNull();
        assertThat(bereinigt.qrReferenz()).isNull();
        assertThat(bereinigt.sicherheit()).doesNotContainKeys(
                Feld.BELEGNUMMER, Feld.RICHTUNG, Feld.BETRAG, Feld.WAEHRUNG, Feld.FAELLIGKEIT, Feld.QR_REFERENZ);
        assertThat(bereinigt).extracting(Vorschlag::partnerName, Vorschlag::titel, Vorschlag::datum)
                .containsExactly("Verein", "Ausgabe", LocalDate.of(2026, 1, 2));
    }
}
