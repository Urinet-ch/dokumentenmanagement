package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet.RealmBenutzer;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.verarbeitung.application.AlarmSpeicher;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbgleichTest {
    private final MandantCode urinet = new MandantCode("urinet");
    private final MandantCode vhf = new MandantCode("vhf-gsk");
    private final ThreadLocal<MandantCode> aktuell = new ThreadLocal<>();
    private final Map<MandantCode, List<Befund>> befunde = new HashMap<>();
    private final Map<MandantCode, Integer> protokoll = new HashMap<>();

    @Test
    void liestAlleSeitenVorDemMandantenabgleichUndOrdnetBefundeGetrenntZu() {
        UUID fremderInhaber = UUID.randomUUID();
        UUID ohneMandant = UUID.randomUUID();
        UUID offenerVorgang = UUID.randomUUID();
        List<RealmBenutzer> ersteSeite = IntStream.range(0, 50)
                .mapToObj(i -> new RealmBenutzer(UUID.randomUUID(), List.of("urinet"), Set.of(Rolle.MITARBEITER)))
                .toList();
        List<Integer> seiten = new ArrayList<>();
        AbgleichIdentitaet identitaet = seite -> {
            assertThat(befunde).isEmpty();
            seiten.add(seite);
            return seite == 0 ? ersteSeite : List.of(
                    new RealmBenutzer(fremderInhaber, List.of("vhf-gsk"), Set.of(Rolle.INHABER)),
                    new RealmBenutzer(ohneMandant, List.of(), Set.of(Rolle.MITARBEITER)));
        };
        AbgleichStand stand = stand(offenerVorgang);

        abgleich(identitaet, stand, false).ausfuehren();

        assertThat(seiten).containsExactly(0, 1);
        assertThat(befunde.get(urinet)).extracting(Befund::schluessel).containsExactlyInAnyOrder(
                "abgleich:" + ohneMandant + ":KEIN_MANDANT",
                "abgleich:" + offenerVorgang + ":VORGANG_OFFEN");
        assertThat(befunde.get(vhf)).extracting(Befund::schluessel)
                .containsExactly("abgleich:" + fremderInhaber + ":ROLLE_OHNE_VORGANG");
        assertThat(protokoll).containsEntry(urinet, 2).containsEntry(vhf, 1);
    }

    @Test
    void unvollstaendigeRealmAbfrageSchreibtKeinenMandantenstand() {
        List<RealmBenutzer> ersteSeite = IntStream.range(0, 50)
                .mapToObj(i -> new RealmBenutzer(UUID.randomUUID(), List.of("urinet"), Set.of()))
                .toList();
        AbgleichIdentitaet identitaet = seite -> {
            if (seite == 0) {
                return ersteSeite;
            }
            throw new IllegalStateException("synthetischer Lesefehler");
        };

        assertThatThrownBy(() -> abgleich(identitaet, stand(null), false).ausfuehren())
                .isInstanceOf(IllegalStateException.class);
        assertThat(protokoll).isEmpty();
        assertThat(befunde).isEmpty();
    }

    @Test
    void fehlerEinesMandantenVerhindertNichtDiePruefungDesAnderen() {
        UUID inhaber = UUID.randomUUID();
        AbgleichIdentitaet identitaet = seite ->
                List.of(new RealmBenutzer(inhaber, List.of("vhf-gsk"), Set.of(Rolle.INHABER)));

        assertThatThrownBy(() -> abgleich(identitaet, stand(null), true).ausfuehren())
                .isInstanceOf(IllegalStateException.class);
        assertThat(befunde).doesNotContainKey(urinet);
        assertThat(befunde.get(vhf)).extracting(Befund::schluessel)
                .containsExactly("abgleich:" + inhaber + ":ROLLE_OHNE_VORGANG");
    }

    private Abgleich abgleich(AbgleichIdentitaet identitaet, AbgleichStand stand, boolean fehlerInUrinet) {
        List<Mandant> mandanten = List.of(mandant(urinet), mandant(vhf));
        MandantRegister register = new MandantRegister() {
            @Override public boolean existiert(MandantCode code) { return true; }
            @Override public void eintragen(Mandant mandant) { throw new UnsupportedOperationException(); }
            @Override public List<Mandant> aktive() { return mandanten; }
            @Override public Optional<Instant> erstellt(MandantCode code) { return Optional.empty(); }
        };
        MandantKontext kontext = new MandantKontext(null, null, null) {
            @Override public <T> T mit(MandantCode code, Supplier<T> arbeit) {
                aktuell.set(code);
                try {
                    return arbeit.get();
                } finally {
                    aktuell.remove();
                }
            }
        };
        VerwaltungTransaktionen transaktionen = new VerwaltungTransaktionen(null) {
            @Override public void neu(Runnable arbeit) { arbeit.run(); }
        };
        ZugriffProtokoll schreiben = eintrag -> protokoll.merge(aktuell.get(), 1, Integer::sum);
        AlarmSpeicher alarme = new AlarmSpeicher() {
            @Override public void abgleichen(MandantCode mandant, Befund.Quelle quelle, Collection<Befund> neu) {
                if (fehlerInUrinet && mandant.equals(urinet)) {
                    throw new IllegalStateException("synthetischer Speicherfehler");
                }
                assertThat(aktuell.get()).isEqualTo(mandant);
                assertThat(quelle).isEqualTo(Befund.Quelle.ABGLEICH);
                befunde.put(mandant, List.copyOf(neu));
            }
            @Override public Optional<Instant> beanspruchen(MandantCode mandant, Befund befund) {
                throw new UnsupportedOperationException();
            }
            @Override public void bestaetigen(MandantCode mandant, Befund befund, Instant anspruch) {
                throw new UnsupportedOperationException();
            }
            @Override public void freigeben(MandantCode mandant, Befund befund, Instant anspruch) {
                throw new UnsupportedOperationException();
            }
        };
        return new Abgleich(identitaet, stand, register, kontext, transaktionen, schreiben, alarme, "urinet");
    }

    private AbgleichStand stand(UUID vorgang) {
        return new AbgleichStand() {
            @Override public Stand lesen(MandantCode mandant) {
                Set<OffenerVorgang> offene = vorgang != null && mandant.equals(urinet)
                        ? Set.of(new OffenerVorgang(vorgang, Optional.empty())) : Set.of();
                return new Stand(Set.of(), Set.of(), offene);
            }
            @Override public boolean unklar(MandantCode mandant, UUID id) {
                return mandant.equals(urinet) && id.equals(vorgang);
            }
        };
    }

    private static Mandant mandant(MandantCode code) {
        return new Mandant(code, code.wert(), code.schema(), code.bucket("urinet-archiv-"), null, true);
    }
}
