package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OeffentlicherWissenschatResourceTest {
    @Test
    void schalterIstJeMandantStandardmaessigAus() {
        var config = new SmallRyeConfigBuilder().withDefaultValues(Map.of(
                "archiv.mandant.urinet.oeffentlich.wissenschat", "true")).build();
        var aus = resource("vhf-gsk", config);
        assertThatThrownBy(() -> aus.fragen("{\"frage\":\"Hallo\"}", null))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_ERLAUBT));
        var an = resource("urinet", config);
        assertThatThrownBy(() -> an.fragen("{", null))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    private static OeffentlicherWissenschatResource resource(String mandant, org.eclipse.microprofile.config.Config config) {
        var aufloesung = new MandantAufloesung(null, null, null) {
            public MandantCode pruefen() { return new MandantCode(mandant); }
        };
        return new OeffentlicherWissenschatResource(null, aufloesung, null, config);
    }
}
