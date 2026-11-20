package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RollenGrenzeTest {

    @Test
    void nurArchivRollenSindVergebbarUndFremdeRealmRollenBleibenUnberuehrt() {
        assertThat(Vergaberegeln.rollenSatzZulaessig(Set.of("inhaber", "mitarbeiter", "treuhaender"))).isTrue();
        assertThat(Vergaberegeln.rollenSatzZulaessig(Set.of("geschaeftsstelle"))).isTrue();
        assertThat(Vergaberegeln.rollenSatzZulaessig(Set.of("realm-admin"))).isFalse();
        assertThat(Vergaberegeln.rollenSatzZulaessig(Set.of())).isFalse();
        assertThat(Rolle.ausRealmRollen(List.of("query-users", "realm-admin"))).isEmpty();
        assertThat(Rolle.ausRealmRollen(List.of("mitarbeiter", "query-users"))).contains(Rolle.MITARBEITER);
    }

    @Test
    void rollenvergabeErlaubtNurGleichStarkeOderSchwaechereRollen() {
        assertThat(Rolle.INHABER.darfVergeben(Rolle.INHABER)).isTrue();
        assertThat(Rolle.INHABER.darfVergeben(Rolle.TREUHAENDER)).isTrue();
        assertThat(Rolle.GESCHAEFTSSTELLE.darfVergeben(Rolle.GESCHAEFTSSTELLE)).isTrue();
        assertThat(Rolle.GESCHAEFTSSTELLE.darfVergeben(Rolle.INHABER)).isFalse();
        assertThat(Rolle.MITARBEITER.darfVergeben(Rolle.GESCHAEFTSSTELLE)).isFalse();
    }

    @Test
    void rangfolgeAusSpecGiltFuerAlleSechzehnPaare() {
        // Req 4.6: inhaber > geschaeftsstelle > mitarbeiter > treuhaender; vergeben darf man gleich starke oder schwächere
        List<Rolle> rang = List.of(Rolle.INHABER, Rolle.GESCHAEFTSSTELLE, Rolle.MITARBEITER, Rolle.TREUHAENDER);
        for (int a = 0; a < rang.size(); a++) {
            for (int z = 0; z < rang.size(); z++) {
                assertThat(rang.get(a).darfVergeben(rang.get(z))).as("%s vergibt %s", rang.get(a), rang.get(z))
                        .isEqualTo(z >= a);
            }
        }
    }
}
