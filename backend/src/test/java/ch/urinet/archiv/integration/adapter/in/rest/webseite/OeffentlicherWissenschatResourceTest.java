package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OeffentlicherWissenschatResourceTest {
    @Test
    void schalterWirdProMandantJeAnfrageGelesenUndVorEingabeGeprueft() {
        var config = new SmallRyeConfigBuilder().withDefaultValues(Map.of(
                "archiv.mandant.urinet.oeffentlich.wissenschat", "true")).build();
        var mandant = new AtomicReference<>("vhf-gsk");
        var resource = resource(mandant, config);
        assertThatThrownBy(() -> resource.fragen("{\"frage\":\"Hallo\"}", null))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_ERLAUBT));
        mandant.set("urinet");
        assertThatThrownBy(() -> resource.fragen("{", null))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        mandant.set("vhf-gsk");
        assertThatThrownBy(() -> resource.fragen("{", null))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_ERLAUBT));
    }

    private static OeffentlicherWissenschatResource resource(AtomicReference<String> mandant,
                                                              org.eclipse.microprofile.config.Config config) {
        var aufloesung = new MandantAufloesung(null, null, null) {
            public MandantCode pruefen() { return new MandantCode(mandant.get()); }
        };
        return new OeffentlicherWissenschatResource(null, aufloesung, null, config);
    }
}
