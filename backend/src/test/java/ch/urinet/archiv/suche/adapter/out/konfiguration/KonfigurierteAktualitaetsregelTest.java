package ch.urinet.archiv.suche.adapter.out.konfiguration;

import ch.urinet.archiv.suche.domain.Aktualitaetsregel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KonfigurierteAktualitaetsregelTest {

    private static AktualitaetsKonfiguration.Mandant eintrag(boolean aktiviert, double faktor, int halbwertzeitTage) {
        return new AktualitaetsKonfiguration.Mandant() {
            public boolean aktiviert() { return aktiviert; }
            public double faktor() { return faktor; }
            public int halbwertzeitTage() { return halbwertzeitTage; }
        };
    }

    private static AktualitaetsKonfiguration konfiguration(Map<String, AktualitaetsKonfiguration.Mandant> mandanten) {
        return () -> mandanten;
    }

    @Test
    void mandantOhneEintragBehaeltDieRerankerReihenfolge() {
        var regel = new KonfigurierteAktualitaetsregel(konfiguration(Map.of("vhf-gsk", eintrag(true, 0.05, 365))));

        assertThat(regel.fuer("vhf-gsk")).isEqualTo(new Aktualitaetsregel(true, 0.05, 365));
        assertThat(regel.fuer("urinet")).isEqualTo(Aktualitaetsregel.AUS);
    }

    @Test
    void ungueltigerWertBrichtDenStartAb() {
        assertThatThrownBy(() -> new KonfigurierteAktualitaetsregel(
                konfiguration(Map.of("vhf-gsk", eintrag(true, 5, 365)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vhf-gsk").hasMessageContaining("faktor");
        assertThatThrownBy(() -> new KonfigurierteAktualitaetsregel(
                konfiguration(Map.of("vhf-gsk", eintrag(true, 0.05, 0)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("halbwertzeit");
    }

    @Test
    void ungueltigerMandantCodeBrichtDenStartAb() {
        assertThatThrownBy(() -> new KonfigurierteAktualitaetsregel(
                konfiguration(Map.of("vhf_gsk", eintrag(true, 0.05, 365)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("«vhf_gsk»");
    }
}
