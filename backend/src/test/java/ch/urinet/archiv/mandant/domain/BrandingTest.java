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

    @Test
    void primaerfarbeOhneAaKontrastFaelltAufStandardfarbeZurueck() {
        for (String farbe : new String[] {"#707070", "#747474", "#777777", "#9a6a00"}) {
            assertThat(Branding.ausKonfiguration("VHF/GSK", null, farbe, "de-CH").primaerfarbe())
                    .as(farbe).isEqualTo(Branding.STANDARD.primaerfarbe());
        }
        Branding hell = Branding.ausKonfiguration("VHF/GSK", null, "#ffffff", "de-CH");
        assertThat(hell.primaerfarbe()).isEqualTo("#ffffff");
        assertThat(hell.textfarbe()).isEqualTo("#05070c");
        Branding dunkel = Branding.ausKonfiguration("VHF/GSK", null, "#1a2b5c", "de-CH");
        assertThat(dunkel.primaerfarbe()).isEqualTo("#1a2b5c");
        assertThat(dunkel.textfarbe()).isEqualTo("#f2efe7");
    }

    @Test
    void logoNurAufEigenerOriginOderAlsPruefbareDatenUri() {
        assertThat(Branding.ausKonfiguration("VHF/GSK", "https://fremd.example/l.png", "#eeb111", "de-CH").logoUrl())
                .isNull();
        assertThat(Branding.ausKonfiguration("VHF/GSK", "//fremd.example/l.png", "#eeb111", "de-CH").logoUrl())
                .isNull();
    }
}
