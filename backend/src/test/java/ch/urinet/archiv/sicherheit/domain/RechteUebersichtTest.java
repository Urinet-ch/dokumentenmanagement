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

    @Test
    void fussnotenStammenAusDerRechtematrix() {
        RechteUebersicht uebersicht = RechteUebersicht.ausRechtematrix();
        for (RechteUebersicht.AktionRechte zeile : uebersicht.aktionen()) {
            assertThat(zeile.fussnoten()).containsOnlyKeys(Rolle.values());
            for (Rolle rolle : Rolle.values()) {
                assertThat(zeile.fussnoten().get(rolle)).isEqualTo(Rechtematrix.fussnoten(rolle, zeile.aktion()));
            }
        }
        assertThat(Rechtematrix.fussnoten(Rolle.GESCHAEFTSSTELLE, Aktion.A22)).containsExactly(Fussnote.GESCHAEFTSSTELLE_OFFEN);
        assertThat(Rechtematrix.fussnoten(Rolle.GESCHAEFTSSTELLE, Aktion.A11)).containsExactly(Fussnote.GESCHAEFTSSTELLE_OFFEN);
        assertThat(Rechtematrix.fussnoten(Rolle.MITARBEITER, Aktion.A4)).containsExactly(Fussnote.NUR_EIGENE);
        assertThat(Rechtematrix.fussnoten(Rolle.INHABER, Aktion.A22)).isEmpty();
        assertThat(Rechtematrix.fussnoten(Rolle.GESCHAEFTSSTELLE, Aktion.A1)).isEmpty();
    }
}
