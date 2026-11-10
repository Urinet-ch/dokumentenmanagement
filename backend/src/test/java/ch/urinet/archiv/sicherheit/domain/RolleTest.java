package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Die stärkste Archiv-Rolle gilt, ohne Archiv-Rolle keine (Req 2.2, entschieden von Fabian am 10.11.2026). */
class RolleTest {

    @Test
    void eineArchivRolle() {
        assertThat(Rolle.ausRealmRollen(List.of("treuhaender", "offline_access"))).contains(Rolle.TREUHAENDER);
    }

    @Test
    void keineArchivRolle() {
        assertThat(Rolle.ausRealmRollen(List.of("offline_access", "INHABER"))).isEmpty();
    }

    @Test
    void mehrereArchivRollenDieStaerksteGilt() {
        assertThat(Rolle.ausRealmRollen(List.of("treuhaender", "inhaber"))).contains(Rolle.INHABER);
        assertThat(Rolle.ausRealmRollen(List.of("mitarbeiter", "geschaeftsstelle"))).contains(Rolle.GESCHAEFTSSTELLE);
        assertThat(Rolle.ausRealmRollen(List.of("inhaber", "geschaeftsstelle"))).contains(Rolle.INHABER);
        // keine Vereinigung: treuhaender liest INTERN, mitarbeiter nicht; es gilt nur mitarbeiter
        assertThat(Rolle.ausRealmRollen(List.of("treuhaender", "mitarbeiter"))).contains(Rolle.MITARBEITER);
    }
}
