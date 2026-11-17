package ch.urinet.archiv.mandant.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

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

    private static final String PNG = Base64.getEncoder().encodeToString(
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0});

    @Test
    void gueltigeDatenUriWirdUebernommen() {
        String uri = "data:image/png;base64," + PNG;
        assertThat(Branding.ausKonfiguration("VHF/GSK", uri, "#eeb111", "de-CH").logoUrl()).isEqualTo(uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "data:image/png;base64,AAAAAAAAAAAA",
            "data:image/png;base64,%%%",
            "data:image/png;base64,PNG*"})
    void ungueltigeDatenUriWirdAbgelehnt(String uri) {
        assertThat(Branding.ausKonfiguration("VHF/GSK", uri, "#eeb111", "de-CH").logoUrl()).isNull();
    }

    @Test
    void zuGrosseDatenUriWirdAbgelehnt() {
        byte[] gross = new byte[100_001];
        gross[0] = (byte) 0x89;
        gross[1] = 'P';
        gross[2] = 'N';
        gross[3] = 'G';
        String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(gross);
        assertThat(Branding.ausKonfiguration("VHF/GSK", uri, "#eeb111", "de-CH").logoUrl()).isNull();
        String ueberlang = "data:image/png;base64," + "A".repeat(140_000);
        assertThat(Branding.ausKonfiguration("VHF/GSK", ueberlang, "#eeb111", "de-CH").logoUrl()).isNull();
    }

    @Test
    void abschliessenderPunktWirdEntfernt() {
        assertThat(Branding.domain("archiv.apps.urinet.ch.")).contains("archiv.apps.urinet.ch");
        assertThat(Branding.domain(".")).isEmpty();
        assertThat(Branding.domain("a..")).isEmpty();
    }
}
