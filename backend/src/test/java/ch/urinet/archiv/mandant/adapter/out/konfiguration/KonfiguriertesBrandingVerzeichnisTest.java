package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KonfiguriertesBrandingVerzeichnisTest {

    private static BrandingKonfiguration.Eintrag eintrag(String domain, String name) {
        return new BrandingKonfiguration.Eintrag() {
            public Optional<String> domain() { return Optional.ofNullable(domain); }
            public String name() { return name; }
            public Optional<String> logoUrl() { return Optional.empty(); }
            public String primaerfarbe() { return "#eeb111"; }
            public String sprache() { return "de-CH"; }
        };
    }

    private static BrandingKonfiguration konfiguration(BrandingKonfiguration.Eintrag... eintraege) {
        Map<String, BrandingKonfiguration.Eintrag> map = new LinkedHashMap<>();
        for (int i = 0; i < eintraege.length; i++) {
            map.put("m" + i, eintraege[i]);
        }
        return () -> map;
    }

    @Test
    void doppelteDomainBrichtDenStartAb() {
        var k = konfiguration(eintrag("a.test.invalid", "A"), eintrag("A.test.invalid.", "B"));
        assertThatThrownBy(() -> new KonfiguriertesBrandingVerzeichnis(k))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mehrfach");
    }

    @Test
    void ungueltigeDomainBrichtDenStartAb() {
        var k = konfiguration(eintrag("kein host/pfad", "A"));
        assertThatThrownBy(() -> new KonfiguriertesBrandingVerzeichnis(k))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ungültige");
    }

    @Test
    void leereDomainWirdUebersprungen() {
        var v = new KonfiguriertesBrandingVerzeichnis(konfiguration(eintrag("", "A"), eintrag("a.test.invalid", "B")));
        assertThat(v.fuerDomain("a.test.invalid")).isPresent();
        assertThat(v.fuerDomain("")).isEmpty();
    }
}
