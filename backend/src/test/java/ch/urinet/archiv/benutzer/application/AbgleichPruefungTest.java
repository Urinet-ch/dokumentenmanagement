package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet.RealmBenutzer;
import ch.urinet.archiv.benutzer.application.AbgleichPruefung.Abweichung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsabweichung;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbgleichPruefungTest {
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");
    private static final UUID EINS = UUID.fromString("722bdcea-3dd3-40b5-aaf6-3a1a067ef945");
    private static final UUID ZWEI = UUID.fromString("5b08357d-2f4d-45a2-a857-3dd166951da1");
    private static final UUID DREI = UUID.fromString("77f9395f-3cce-494a-a017-094d7e664359");
    private static final UUID VIER = UUID.fromString("a05275ba-518f-44cc-96d2-8e3295716cf9");

    @Test
    void meldetAbweichungenNurImZustaendigenMandanten() {
        var realm = List.of(
                new RealmBenutzer(EINS, List.of("urinet"), Set.of(Rolle.INHABER, Rolle.GESCHAEFTSSTELLE)),
                new RealmBenutzer(ZWEI, List.of("vhf-gsk"), Set.of(Rolle.INHABER)),
                new RealmBenutzer(DREI, List.of(), Set.of(Rolle.MITARBEITER, Rolle.INHABER)),
                new RealmBenutzer(VIER, List.of("urinet", "vhf-gsk"), Set.of(Rolle.MITARBEITER, Rolle.INHABER)));

        assertThat(AbgleichPruefung.vergleichen(URINET, true, realm,
                Set.of(Rolle.INHABER, Rolle.MITARBEITER), Set.of(EINS), Set.of(), Set.of(DREI)))
                .containsExactlyInAnyOrder(new Abweichung(Verwaltungsabweichung.MEHRERE_ROLLEN, EINS),
                        new Abweichung(Verwaltungsabweichung.FREMDE_ROLLE, EINS),
                        new Abweichung(Verwaltungsabweichung.ROLLE_OHNE_VORGANG, EINS),
                        new Abweichung(Verwaltungsabweichung.KEIN_MANDANT, DREI),
                        new Abweichung(Verwaltungsabweichung.MEHRERE_ROLLEN, DREI),
                        new Abweichung(Verwaltungsabweichung.MEHRERE_MANDANTEN, VIER),
                        new Abweichung(Verwaltungsabweichung.MEHRERE_ROLLEN, VIER),
                        new Abweichung(Verwaltungsabweichung.VORGANG_OFFEN, DREI));
        assertThat(AbgleichPruefung.vergleichen(VHF, false, realm,
                Set.of(Rolle.INHABER), Set.of(ZWEI), Set.of(ZWEI), Set.of())).isEmpty();
    }

    @Test
    void fremderOderMehrdeutigZugeordneterVerwalteterBenutzerGiltAlsFehlend() {
        var realm = List.of(
                new RealmBenutzer(EINS, List.of("vhf-gsk"), Set.of(Rolle.MITARBEITER)),
                new RealmBenutzer(ZWEI, List.of("urinet", "vhf-gsk"), Set.of(Rolle.MITARBEITER)),
                new RealmBenutzer(DREI, List.of("urinet"), Set.of()));

        assertThat(AbgleichPruefung.vergleichen(URINET, false, realm,
                Set.of(Rolle.MITARBEITER), Set.of(EINS, ZWEI, DREI, VIER), Set.of(), Set.of()))
                .containsExactlyInAnyOrder(new Abweichung(Verwaltungsabweichung.BENUTZER_FEHLT, EINS),
                        new Abweichung(Verwaltungsabweichung.BENUTZER_FEHLT, ZWEI),
                        new Abweichung(Verwaltungsabweichung.BENUTZER_FEHLT, VIER));
    }

    @Test
    void doppelteKennungenWerdenNichtAlsVollstaendigerAbgleichAkzeptiert() {
        var benutzer = new RealmBenutzer(EINS, List.of("urinet"), Set.of(Rolle.INHABER));

        assertThatThrownBy(() -> AbgleichPruefung.vergleichen(URINET, true, List.of(benutzer, benutzer),
                Set.of(Rolle.INHABER), Set.of(), Set.of(EINS), Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
