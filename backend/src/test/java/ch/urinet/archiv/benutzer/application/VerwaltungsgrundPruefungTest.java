package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsgrund;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerwaltungsgrundPruefungTest {
    @Test
    void nimmtNurFesteKategorienAn() {
        for (Verwaltungsgrund grund : Verwaltungsgrund.values()) {
            assertThat(VerwaltungsgrundPruefung.pruefen(grund.name())).isEqualTo(grund);
        }
        for (String ungueltig : new String[]{null, "", "sicherheit", "synthetischer Freitext", "SICHERHEIT\n"}) {
            assertThatThrownBy(() -> VerwaltungsgrundPruefung.pruefen(ungueltig))
                    .isInstanceOfSatisfying(KonfigurationUngueltig.class, fehler ->
                            assertThat(fehler.befunde()).singleElement().satisfies(befund -> {
                                assertThat(befund.code()).isEqualTo("GRUND_UNGUELTIG");
                                assertThat(befund.meldung()).doesNotContain("synthetischer Freitext");
                            }));
        }
    }
}
