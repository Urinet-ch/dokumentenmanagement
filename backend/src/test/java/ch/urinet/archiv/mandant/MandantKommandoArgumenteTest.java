package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MandantKommandoArgumenteTest {

    @Test
    void rollenWerdenAlsOptionGelesen() {
        assertThat(MandantKommando.lesen(List.of("anlegen", "probe", "--rollen", "geschaeftsstelle,inhaber")))
                .hasValueSatisfying(aufruf -> assertThat(aufruf.rollen())
                        .hasValueSatisfying(rollen -> assertThat(rollen.werte())
                                .containsExactly("geschaeftsstelle", "inhaber")));
        assertThat(MandantKommando.lesen(List.of("anlegen", "probe")))
                .hasValueSatisfying(aufruf -> assertThat(aufruf.rollen()).isEmpty());
    }

    @Test
    void ungueltigeRollenWerdenVorDemStartAbgewiesen() {
        for (String rollen : List.of("", "inhaber,", "inhaber,inhaber", "administrator", "inhaber, mitarbeiter",
                "inhaber".repeat(100))) {
            assertThat(MandantKommando.gueltig(List.of("anlegen", "probe", "--rollen", rollen)))
                    .as(rollen).isFalse();
        }
        assertThat(MandantKommando.gueltig(List.of("anlegen", "probe", "--rollen", "inhaber",
                "--rollen", "mitarbeiter"))).isFalse();
        assertThat(MandantKommando.gueltig(List.of("anlegen", "probe", "--rollen"))).isFalse();
    }
}
