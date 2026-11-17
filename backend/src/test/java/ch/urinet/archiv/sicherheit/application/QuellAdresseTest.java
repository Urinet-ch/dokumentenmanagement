package ch.urinet.archiv.sicherheit.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quelladresse der Anfrage: X-Real-Ip zählt nur von einem Peer im konfigurierten Proxy-Netz (OWASP API4, F2). */
class QuellAdresseTest {

    private final QuellAdresse proxyNetz = new QuellAdresse(List.of("172.16.0.0/12", "::1/128"));

    @Test
    void headerVomVertrauenswuerdigenProxyGilt() {
        assertThat(proxyNetz.aufloesen("172.18.0.5", "203.0.113.17")).contains("203.0.113.17");
        assertThat(proxyNetz.aufloesen("::1", " 2001:db8::7 ")).contains("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void gefaelschterHeaderEinesFremdenPeersIstWirkungslos() {
        assertThat(proxyNetz.aufloesen("198.51.100.9", "203.0.113.17")).contains("198.51.100.9");
        assertThat(proxyNetz.aufloesen("172.32.0.1", "203.0.113.17")).as("knapp ausserhalb des Netzes")
                .contains("172.32.0.1");
    }

    @Test
    void ohneNetzWirdDerHeaderNieAusgewertet() {
        QuellAdresse ohne = new QuellAdresse(List.of());

        assertThat(ohne.ohneNetze()).isTrue();
        assertThat(ohne.aufloesen("172.18.0.5", "203.0.113.17")).contains("172.18.0.5");
    }

    @Test
    void ungueltigerOderMehrfacherHeaderFaelltAufDieVerbindungZurueck() {
        for (String wert : List.of("203.0.113.17, 198.51.100.1", "kaputt", "", "999.1.1.1", "203.0.113.17\r\nX: y")) {
            assertThat(proxyNetz.aufloesen("172.18.0.5", wert)).as(wert).contains("172.18.0.5");
        }
        assertThat(proxyNetz.aufloesen("172.18.0.5", null)).contains("172.18.0.5");
    }

    @Test
    void ohneVerbindungsadresseKeineQuelle() {
        assertThat(proxyNetz.aufloesen(null, "203.0.113.17")).isEqualTo(Optional.empty());
    }

    @Test
    void ungueltigesNetzBrichtDenStartAb() {
        assertThatThrownBy(() -> new QuellAdresse(List.of("172.16.0.0/40"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuellAdresse(List.of("kein-netz"))).isInstanceOf(IllegalArgumentException.class);
    }
}
