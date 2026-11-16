package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verlauf der öffentlichen Gespräche nur im Speicher (Spec 03, design.md: 6 Nachrichten; Req 6.5). */
class OeffentlicheGespraecheTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    private static final class Uhr extends Clock {
        private Instant jetzt = Instant.parse("2071-03-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }
    }

    private static Lesezugriff zugriff(String webseite, String client) {
        return new Lesezugriff(webseite, Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.OEFFENTLICH, Optional.ofNullable(client));
    }

    @Test
    void verlaufBleibtAufDieLetztenNachrichtenBegrenztUndBeginntMitEinerFrage() {
        OeffentlicheGespraeche g = new OeffentlicheGespraeche(Duration.ofMinutes(30), 10, 6, new Uhr());
        Lesezugriff z = zugriff("w", "a".repeat(64));
        UUID id = g.oeffnen(null, URINET, z).id();
        for (int i = 1; i <= 5; i++) {
            g.merken(id, URINET, z, "Frage " + i, "Antwort " + i);
        }
        var verlauf = g.oeffnen(id, URINET, z).verlauf();
        assertThat(verlauf).hasSize(6);
        assertThat(verlauf.getFirst().rolle()).isEqualTo(ChatRolle.BENUTZER);
        assertThat(verlauf.getFirst().inhalt()).isEqualTo("Frage 3");
        assertThat(verlauf.getLast().inhalt()).isEqualTo("Antwort 5");
    }

    @Test
    void einFremderClientEineAndereWebseiteOderMandantBekommtEinNeuesGespraech() {
        OeffentlicheGespraeche g = new OeffentlicheGespraeche(Duration.ofMinutes(30), 10, 6, new Uhr());
        Lesezugriff anna = zugriff("w", "a".repeat(64));
        UUID id = g.oeffnen(null, URINET, anna).id();
        g.merken(id, URINET, anna, "Frage", "Antwort");
        for (var fremd : java.util.List.of(
                g.oeffnen(id, URINET, zugriff("w", "b".repeat(64))),
                g.oeffnen(id, URINET, zugriff("andere", "a".repeat(64))),
                g.oeffnen(id, new MandantCode("vhf-gsk"), anna))) {
            assertThat(fremd.id()).isNotEqualTo(id);
            assertThat(fremd.verlauf()).isEmpty();
        }
        assertThat(g.oeffnen(id, URINET, anna).verlauf()).hasSize(2);
    }

    @Test
    void unbekannteIdBeginntStillEinNeuesGespraech() {
        OeffentlicheGespraeche g = new OeffentlicheGespraeche(Duration.ofMinutes(30), 10, 6, new Uhr());
        UUID unbekannt = UUID.randomUUID();
        var geoeffnet = g.oeffnen(unbekannt, URINET, zugriff("w", null));
        assertThat(geoeffnet.id()).isNotEqualTo(unbekannt);
        assertThat(geoeffnet.verlauf()).isEmpty();
    }

    @Test
    void nachDerDauerIstDasGespraechVergessenUndDerSpeicherIstBegrenzt() {
        Uhr uhr = new Uhr();
        OeffentlicheGespraeche g = new OeffentlicheGespraeche(Duration.ofMinutes(30), 2, 6, uhr);
        Lesezugriff z = zugriff("w", "a".repeat(64));
        UUID erstes = g.oeffnen(null, URINET, z).id();
        g.merken(erstes, URINET, z, "Eins", "A");
        UUID zweites = g.oeffnen(null, URINET, z).id();
        g.merken(zweites, URINET, z, "Zwei", "B");
        UUID drittes = g.oeffnen(null, URINET, z).id();
        g.merken(drittes, URINET, z, "Drei", "C");
        assertThat(g.anzahl()).as("höchstens max-gespraeche").isEqualTo(2);
        assertThat(g.oeffnen(erstes, URINET, z).verlauf()).as("das älteste fiel weg").isEmpty();
        uhr.jetzt = uhr.jetzt.plus(Duration.ofMinutes(31));
        assertThat(g.anzahl()).isZero();
        assertThat(g.oeffnen(drittes, URINET, z).verlauf()).isEmpty();
    }

    @Test
    void ungueltigeKonfigurationBrichtAb() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new OeffentlicheGespraeche(Duration.ZERO, 10, 6, new Uhr())).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new OeffentlicheGespraeche(Duration.ofMinutes(1), 0, 6, new Uhr())).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new OeffentlicheGespraeche(Duration.ofMinutes(1), 1, 1, new Uhr())).isInstanceOf(IllegalArgumentException.class);
    }
}
