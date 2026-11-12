package ch.urinet.archiv.dokument.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KiSicherheitTest {

    private static final BigDecimal SCHWELLE = new BigDecimal("0.8");

    @Test
    void markiertFelderUnterDerSchwelleUndOhneSicherheit() {
        var unsicher = KiSicherheit.unsicher(Set.of("titel", "betrag", "datum", "art"),
                Map.of("art", new BigDecimal("0.95"), "titel", new BigDecimal("0.8"), "datum", new BigDecimal("0.79")),
                SCHWELLE);

        assertThat(unsicher).containsExactly("betrag", "datum");
    }

    @Test
    void bewerteteFelderOhneWertZaehlenMit() {
        assertThat(KiSicherheit.unsicher(Set.of(), Map.of("faelligkeit", new BigDecimal("0.3")), SCHWELLE))
                .containsExactly("faelligkeit");
    }

    @Test
    void ohneVorschlagIstNichtsUnsicher() {
        assertThat(KiSicherheit.unsicher(Set.of(), Map.of(), SCHWELLE)).isEmpty();
    }
}
