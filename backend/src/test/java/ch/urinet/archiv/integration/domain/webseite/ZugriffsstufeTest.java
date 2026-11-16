package ch.urinet.archiv.integration.domain.webseite;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Zugriffsstufen, Kappung und Filter-Eingabe (Spec 03, Aufgabe 11.1, Req 3.7, 5.3). */
class ZugriffsstufeTest {

    @Test
    void ordnungUndStufenBisUndMit() {
        assertThat(Zugriffsstufe.OEFFENTLICH.bisUndMit()).containsExactly("oeffentlich");
        assertThat(Zugriffsstufe.MITGLIEDER.bisUndMit()).containsExactly("oeffentlich", "mitglieder");
        assertThat(Zugriffsstufe.VORSTAND.bisUndMit()).containsExactly("oeffentlich", "mitglieder", "vorstand");
        assertThat(Zugriffsstufe.VORSTAND.hoeherAls(Zugriffsstufe.MITGLIEDER)).isTrue();
        assertThat(Zugriffsstufe.MITGLIEDER.hoeherAls(Zugriffsstufe.MITGLIEDER)).isFalse();
    }

    @Test
    void codesSindGenau() {
        for (Zugriffsstufe s : Zugriffsstufe.values()) {
            assertThat(Zugriffsstufe.ausCode(s.code())).contains(s);
        }
        for (String falsch : new String[] {"Mitglieder", "VORSTAND", "", " vorstand", "mitglieder ", "alle", null}) {
            assertThat(Zugriffsstufe.ausCode(falsch)).as("«%s»", falsch).isEqualTo(Optional.empty());
        }
    }

    @Test
    void wirksameStufeIstNieHoeherAlsDieDesSchluessels() {
        Lesezugriff z = new Lesezugriff("w", Zugriffsstufe.MITGLIEDER, Zugriffsstufe.VORSTAND, Optional.empty());
        assertThat(z.wirksameStufe()).isEqualTo(Zugriffsstufe.MITGLIEDER);
        assertThat(z.stufen()).containsExactly("oeffentlich", "mitglieder");
        assertThat(z.sichtbar(Zugriffsstufe.MITGLIEDER)).isTrue();
        assertThat(z.sichtbar(Zugriffsstufe.VORSTAND)).isFalse();
    }
}
