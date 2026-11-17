package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KonfigurationCacheTest {

    @Test
    void gleicheVersionsnummernUndInvalidierungBleibenMandantGetrennt() {
        var cache = new KonfigurationCache();
        var urinet = new MandantCode("urinet");
        var vhf = new MandantCode("vhf-gsk");

        cache.speichern(urinet, konfiguration("ART_A"));
        cache.speichern(vhf, konfiguration("ART_B"));

        assertThat(cache.finden(urinet, 1)).contains(konfiguration("ART_A"));
        assertThat(cache.finden(vhf, 1)).contains(konfiguration("ART_B"));

        cache.invalidieren(urinet);

        assertThat(cache.finden(urinet, 1)).isEmpty();
        assertThat(cache.finden(vhf, 1)).contains(konfiguration("ART_B"));
    }

    private static Konfiguration konfiguration(String code) {
        var art = new Konfiguration.Art(code, code, null, List.of("frei"), List.of(),
                "{\"type\":\"object\"}", "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true);
        return new Konfiguration(1, new Konfiguration.Einstellungen("12-31",
                new Konfiguration.Kennungen(null, List.of())), List.of(art));
    }
}
