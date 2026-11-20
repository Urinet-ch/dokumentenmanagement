package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FunktionenTest {

    private static Konfiguration.Art art(boolean aktiv, String... gruppen) {
        return new Konfiguration.Art("PROBE", "Probe", null, List.of(gruppen), List.of(), null, "NORMAL",
                new Konfiguration.Aufbewahrung(null, null), false, aktiv);
    }

    @Test
    void finanzIstNurBeiMindestensEinerAktivenFinanzArtVerfuegbar() {
        Funktionen ohneFinanz = new Funktionen(() -> new Konfiguration(1, null,
                List.of(art(true, "allgemein"), art(false, "finanz"))));
        Funktionen mitFinanz = new Funktionen(() -> new Konfiguration(1, null,
                List.of(art(false, "finanz"), art(true, "finanz"))));

        assertThat(ohneFinanz.finanzVerfuegbar()).isFalse();
        assertThat(mitFinanz.finanzVerfuegbar()).isTrue();
    }
}
