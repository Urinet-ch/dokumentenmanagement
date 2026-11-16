package ch.urinet.archiv.integration.application.webseite;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Grenzen der Plausibilitätsprüfung der Fundstelle (Req 5.7): Seite liegt in 1 bis {@code dokument.seiten}. */
class WebseiteSucheTest {

    @Test
    void seiteInnerhalbDesBereichsBleibtErhalten() {
        assertThat(WebseiteSuche.plausibel(1, 12)).isEqualTo(1);
        assertThat(WebseiteSuche.plausibel(7, 12)).isEqualTo(7);
        assertThat(WebseiteSuche.plausibel(12, 12)).isEqualTo(12);
    }

    @Test
    void seiteAusserhalbDesBereichsFaelltWeg() {
        assertThat(WebseiteSuche.plausibel(0, 12)).isNull();
        assertThat(WebseiteSuche.plausibel(-1, 12)).isNull();
        assertThat(WebseiteSuche.plausibel(13, 12)).isNull();
    }

    @Test
    void ohneSeiteOderOhneSeitenzahlKeineFundstelle() {
        assertThat(WebseiteSuche.plausibel(null, 12)).isNull();
        assertThat(WebseiteSuche.plausibel(5, null)).isNull();
        assertThat(WebseiteSuche.plausibel(null, null)).isNull();
    }
}
