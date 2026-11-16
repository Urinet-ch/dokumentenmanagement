package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Sichtregel je Rolle aus der Rechtematrix (A2–A5, A7; Req 2.3, 2.8–2.10, 2.21, 2.24). */
class SichtregelTest {

    @Test
    void inhaberUndGeschaeftsstelleSehenAlles() {
        for (Rolle rolle : new Rolle[] {Rolle.INHABER, Rolle.GESCHAEFTSSTELLE}) {
            Sichtregel regel = Sichtregel.fuer(new Benutzer("b", rolle));
            assertThat(regel.unbestaetigt()).as(rolle.name()).isEqualTo(Sichtregel.Unbestaetigt.ALLE);
            assertThat(regel.uneingeschraenkt()).as(rolle.name()).isTrue();
            assertThat(regel.eingangskorbNurErfasste()).as(rolle.name()).isFalse();
        }
    }

    @Test
    void mitarbeiterSiehtNormalEigenePersonalUndUnbestaetigtNurSelbstErfasste() {
        Sichtregel regel = Sichtregel.fuer(new Benutzer("joel", Rolle.MITARBEITER));
        assertThat(regel).isEqualTo(new Sichtregel("joel", Sichtregel.Unbestaetigt.ERFASSTE, true, false,
                Sichtregel.Personal.NUR_EIGENE, true));
        assertThat(regel.uneingeschraenkt()).isFalse();
    }

    @Test
    void treuhaenderLiestAlleBestaetigtenAberKeineUnbestaetigten() {
        Sichtregel regel = Sichtregel.fuer(new Benutzer("t", Rolle.TREUHAENDER));
        assertThat(regel).isEqualTo(new Sichtregel("t", Sichtregel.Unbestaetigt.KEINE, true, true,
                Sichtregel.Personal.ALLE, false));
        assertThat(regel.uneingeschraenkt()).isFalse();
    }

    @Test
    void webseitenSichtHatKeinenBenutzerKeineRechteUndDieStufen() {
        Sichtregel regel = Sichtregel.webseite(java.util.List.of("oeffentlich", "mitglieder"));
        assertThat(regel.istWebseite()).isTrue();
        assertThat(regel.webseitenStufen()).containsExactly("oeffentlich", "mitglieder");
        assertThat(regel.intern()).isFalse();
        assertThat(regel.personal()).isEqualTo(Sichtregel.Personal.KEINE);
        assertThat(regel.unbestaetigt()).isEqualTo(Sichtregel.Unbestaetigt.KEINE);
        assertThat(regel.uneingeschraenkt()).isFalse();
    }

    @Test
    void webseitenSichtOhneStufenIstVerboten() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Sichtregel.webseite(java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Sichtregel.webseite(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appSichtIstKeineWebseitenSicht() {
        assertThat(new Sichtregel("t", Sichtregel.Unbestaetigt.ALLE, true, true, Sichtregel.Personal.ALLE, false)
                .istWebseite()).isFalse();
    }
}
