package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 03, Req 6.4, 15.1: 20 Fragen je Client und Stunde, Grenze je Webseite, Gleichzeitigkeit; abgelehnte zählen nie. */
class OeffentlicheGrenzenTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    /** Uhr, die der Test vorstellt. */
    private static final class Uhr extends Clock {
        private Instant jetzt = Instant.parse("2071-03-01T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }

        void vor(Duration dauer) {
            jetzt = jetzt.plus(dauer);
        }
    }

    private static Lesezugriff zugriff(String webseite, String client) {
        return new Lesezugriff(webseite, Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.OEFFENTLICH, Optional.ofNullable(client));
    }

    private static void frage(OeffentlicheGrenzen g, Lesezugriff z) {
        g.annehmen(URINET, z).freigeben();
    }

    @Test
    void zwanzigFragenJeClientUndStundeDieEinundzwanzigsteWirdAbgelehnt() {
        Uhr uhr = new Uhr();
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(20, 600, 1, 4, uhr);
        Lesezugriff anna = zugriff("w", "a".repeat(64));
        for (int i = 0; i < 20; i++) {
            frage(g, anna);
            uhr.vor(Duration.ofMinutes(1));
        }
        assertThatThrownBy(() -> g.annehmen(URINET, anna)).isInstanceOfSatisfying(ZuVieleAnfragen.class, e -> {
            assertThat(e.code()).isEqualTo(Fehlercode.ZU_VIELE_ANFRAGEN);
            assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.STUNDENLIMIT);
            // die älteste Frage liegt 20 Minuten zurück: noch rund 40 Minuten
            assertThat(e.wartezeitSekunden()).isBetween(39 * 60L, 41 * 60L);
        });
        assertThat(g.belegt()).as("abgelehnt belegt keinen Platz").isZero();
        // ein anderer Client derselben Webseite ist nicht betroffen
        frage(g, zugriff("w", "b".repeat(64)));
    }

    @Test
    void nachEinerStundeIstDieGrenzeFrei() {
        Uhr uhr = new Uhr();
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(2, 600, 1, 4, uhr);
        Lesezugriff z = zugriff("w", "a".repeat(64));
        frage(g, z);
        frage(g, z);
        assertThatThrownBy(() -> g.annehmen(URINET, z)).isInstanceOf(ZuVieleAnfragen.class);
        uhr.vor(Duration.ofHours(1).plusSeconds(1));
        frage(g, z);
    }

    @Test
    void einAbgelehnterVersuchVerlaengertDieSperreNicht() {
        Uhr uhr = new Uhr();
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(1, 600, 1, 4, uhr);
        Lesezugriff z = zugriff("w", "a".repeat(64));
        frage(g, z);
        for (int i = 0; i < 10; i++) {
            uhr.vor(Duration.ofMinutes(5));
            assertThatThrownBy(() -> g.annehmen(URINET, z)).isInstanceOf(ZuVieleAnfragen.class);
        }
        uhr.vor(Duration.ofMinutes(11));
        frage(g, z);
    }

    @Test
    void einWechselDesHashsUmgehtDieGrenzeJeWebseiteNicht() {
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(20, 5, 1, 4, new Uhr());
        for (int i = 0; i < 5; i++) {
            frage(g, zugriff("w", String.format("%064x", i)));
        }
        assertThatThrownBy(() -> g.annehmen(URINET, zugriff("w", String.format("%064x", 99))))
                .isInstanceOfSatisfying(ZuVieleAnfragen.class,
                        e -> assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.STUNDENLIMIT));
        frage(g, zugriff("andere-webseite", "c".repeat(64)));
    }

    @Test
    void ohneHashZaehlenDieBesucherDerWebseiteZusammen() {
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(2, 600, 1, 4, new Uhr());
        frage(g, zugriff("w", null));
        frage(g, zugriff("w", null));
        assertThatThrownBy(() -> g.annehmen(URINET, zugriff("w", null))).isInstanceOf(ZuVieleAnfragen.class);
        frage(g, zugriff("w", "d".repeat(64)));
    }

    @Test
    void jeClientEineLaufendeFrageUndGesamtHoechstensDieHoechstzahl() {
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(20, 600, 1, 2, new Uhr());
        OeffentlicheGrenzen.Platz erster = g.annehmen(URINET, zugriff("w", "a".repeat(64)));
        assertThatThrownBy(() -> g.annehmen(URINET, zugriff("w", "a".repeat(64)))).isInstanceOfSatisfying(
                ZuVieleAnfragen.class, e -> {
                    assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.LAEUFT);
                    assertThat(e.wartezeitSekunden()).isEqualTo(5);
                });
        OeffentlicheGrenzen.Platz zweiter = g.annehmen(URINET, zugriff("w", "b".repeat(64)));
        assertThatThrownBy(() -> g.annehmen(URINET, zugriff("w", "c".repeat(64)))).isInstanceOfSatisfying(
                ZuVieleAnfragen.class, e -> assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.LAEUFT));
        erster.freigeben();
        erster.freigeben();
        assertThat(g.belegt()).isEqualTo(1);
        zweiter.freigeben();
        assertThat(g.belegt()).isZero();
    }

    @Test
    void eineAbgelehnteFrageWegenLaufenderFrageZaehltNicht() {
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(2, 600, 1, 4, new Uhr());
        Lesezugriff z = zugriff("w", "a".repeat(64));
        OeffentlicheGrenzen.Platz platz = g.annehmen(URINET, z);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> g.annehmen(URINET, z)).isInstanceOf(ZuVieleAnfragen.class);
        }
        platz.freigeben();
        frage(g, z);
    }

    @Test
    void mandantenSindGetrennt() {
        OeffentlicheGrenzen g = new OeffentlicheGrenzen(1, 600, 1, 4, new Uhr());
        Lesezugriff z = zugriff("w", "a".repeat(64));
        g.annehmen(URINET, z).freigeben();
        g.annehmen(new MandantCode("vhf-gsk"), z).freigeben();
    }

    @Test
    void ungueltigeGrenzenBrechenDenStartAb() {
        assertThatThrownBy(() -> new OeffentlicheGrenzen(0, 1, 1, 1, new Uhr())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OeffentlicheGrenzen(1, 0, 1, 1, new Uhr())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OeffentlicheGrenzen(1, 1, 0, 1, new Uhr())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OeffentlicheGrenzen(1, 1, 1, 0, new Uhr())).isInstanceOf(IllegalArgumentException.class);
    }
}
