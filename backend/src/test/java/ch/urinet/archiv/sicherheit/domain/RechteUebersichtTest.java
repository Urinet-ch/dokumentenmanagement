package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RechteUebersichtTest {

    @Test
    void jedeZelleStammtAusDerRechtematrix() {
        RechteUebersicht uebersicht = RechteUebersicht.ausRechtematrix();

        assertThat(uebersicht.aktionen()).hasSize(Aktion.values().length);
        for (int i = 0; i < Aktion.values().length; i++) {
            Aktion aktion = Aktion.values()[i];
            RechteUebersicht.AktionRechte zeile = uebersicht.aktionen().get(i);
            assertThat(zeile.aktion()).isEqualTo(aktion);
            assertThat(zeile.bezeichnung()).isEqualTo(aktion.bezeichnung());
            assertThat(zeile.rollen()).containsOnlyKeys(Rolle.values());
            for (Rolle rolle : Rolle.values()) {
                assertThat(zeile.rollen().get(rolle)).isEqualTo(Rechtematrix.ergebnis(rolle, aktion));
            }
        }
    }
}
