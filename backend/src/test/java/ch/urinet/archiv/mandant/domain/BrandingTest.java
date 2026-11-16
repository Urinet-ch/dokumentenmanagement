package ch.urinet.archiv.mandant.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrandingTest {

    @Test
    void domainIstEinHostnameOhnePortOderPfad() {
        assertThat(Branding.domain("VHF-GSK.test.invalid")).contains("vhf-gsk.test.invalid");
        assertThat(Branding.domain("bad.test.invalid/path")).isEmpty();
        assertThat(Branding.domain("127.0.0.1")).isEmpty();
        assertThat(Branding.domain("")).isEmpty();
    }

    @Test
    void unsichereKonfigurationFaelltAufNeutraleWerteZurueck() {
        Branding branding = Branding.ausKonfiguration("<script>alert(1)</script>", "data:image/svg+xml;base64,PHN2Zz4=",
                "url(javascript:alert(1))", "en-US");

        assertThat(branding).isEqualTo(Branding.STANDARD);
    }

    @Test
    void primaerfarbeErhaeltEineKontrastfarbeUndNurRasterlogos() {
        Branding branding = Branding.ausKonfiguration("VHF/GSK", "/logos/vhf.webp", "#eeb111", "de-CH");

        assertThat(branding.textfarbe()).isEqualTo("#05070c");
        assertThat(branding.logoUrl()).isEqualTo("/logos/vhf.webp");
        assertThat(Branding.ausKonfiguration("VHF/GSK", "/logos/logo.svg", "#eeb111", "de-CH").logoUrl())
                .isNull();
    }
}
