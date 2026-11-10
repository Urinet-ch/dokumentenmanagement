package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Genau eine Archiv-Rolle, sonst keine (fail closed, Req 2.2). */
class RolleTest {

    @Test
    void genauEineArchivRolle() {
        assertThat(Rolle.ausRealmRollen(List.of("treuhaender", "offline_access"))).contains(Rolle.TREUHAENDER);
    }

    @Test
    void keineOderMehrereArchivRollen() {
        assertThat(Rolle.ausRealmRollen(List.of("offline_access", "INHABER"))).isEmpty();
        assertThat(Rolle.ausRealmRollen(List.of("inhaber", "treuhaender"))).isEmpty();
    }
}
