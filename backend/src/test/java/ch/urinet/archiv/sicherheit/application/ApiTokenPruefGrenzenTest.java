package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zähler der Schlüsselprüfung (OWASP API4, F2): Auffüllen, Wartezeit, Obergrenze, Sammel-Bucket, IPv6-Präfix. */
class ApiTokenPruefGrenzenTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static final class Uhr extends Clock {
        private Instant jetzt = START;

        void weiter(Duration dauer) {
            jetzt = jetzt.plus(dauer);
        }

        @Override
        public java.time.ZoneId getZone() {
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
    }

    private final Uhr uhr = new Uhr();

    private static long wartezeit(ApiTokenPruefGrenzen grenzen, String quelle) {
        try {
            grenzen.pruefen(Optional.of(quelle));
            return 0;
        } catch (ZuVieleAnfragen e) {
            assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.SCHLUESSELPRUEFUNG);
            return e.wartezeitSekunden();
        }
    }

    @Test
    void spitzeDannWartezeitNachDerNachfuellrate() {
        ApiTokenPruefGrenzen grenzen = new ApiTokenPruefGrenzen(new ApiTokenPruefGrenzen.Einstellung(0.5, 2, 10), uhr);

        assertThat(wartezeit(grenzen, "192.0.2.1")).isZero();
        assertThat(wartezeit(grenzen, "192.0.2.1")).isZero();
        assertThat(wartezeit(grenzen, "192.0.2.1")).isEqualTo(2);

        uhr.weiter(Duration.ofSeconds(2));
        assertThat(wartezeit(grenzen, "192.0.2.1")).isZero();
    }

    @Test
    void wartezeitIstAufEineStundeBegrenzt() {
        ApiTokenPruefGrenzen grenzen = new ApiTokenPruefGrenzen(
                new ApiTokenPruefGrenzen.Einstellung(0.00001, 1, 10), uhr);
        grenzen.pruefen(Optional.of("192.0.2.1"));

        assertThat(wartezeit(grenzen, "192.0.2.1")).isEqualTo(3600);
    }

    @Test
    void andereQuelleBleibtVonFremdenFehlversuchenUnberuehrt() {
        ApiTokenPruefGrenzen grenzen = new ApiTokenPruefGrenzen(new ApiTokenPruefGrenzen.Einstellung(0.01, 1, 10), uhr);
        grenzen.pruefen(Optional.of("192.0.2.1"));

        assertThat(wartezeit(grenzen, "192.0.2.1")).isPositive();
        assertThat(wartezeit(grenzen, "192.0.2.2")).isZero();
    }

    @Test
    void ipv6ZaehltJePraefix64() {
        ApiTokenPruefGrenzen grenzen = new ApiTokenPruefGrenzen(new ApiTokenPruefGrenzen.Einstellung(0.01, 1, 10), uhr);
        grenzen.pruefen(Optional.of("2001:db8:1:2::1"));

        assertThat(wartezeit(grenzen, "2001:db8:1:2:ffff:eeee:dddd:cccc")).isPositive();
        assertThat(wartezeit(grenzen, "2001:db8:1:3::1")).isZero();
        assertThat(grenzen.quellen()).isEqualTo(2);
    }

    @Test
    void vollerSammelBucketGiltFuerNeueQuellenAberNichtFuerBekannte() {
        ApiTokenPruefGrenzen grenzen = new ApiTokenPruefGrenzen(new ApiTokenPruefGrenzen.Einstellung(0.01, 2, 2), uhr);
        grenzen.pruefen(Optional.of("192.0.2.1"));
        grenzen.pruefen(Optional.of("192.0.2.2"));

        // Tabelle voll mit nicht erholten Buckets: neue Quellen teilen sich den Sammel-Bucket (Spitze 2)
        assertThat(wartezeit(grenzen, "192.0.2.3")).isZero();
        assertThat(wartezeit(grenzen, "192.0.2.4")).isZero();
        assertThat(wartezeit(grenzen, "192.0.2.5")).isPositive();
        assertThat(grenzen.quellen()).isEqualTo(2);
        // bekannte Quellen haben ihren eigenen Bucket
        assertThat(wartezeit(grenzen, "192.0.2.1")).isZero();
    }

    @Test
    void erholteBucketsWerdenBaldVerdraengt() {
        ApiTokenPruefGrenzen grenzen = new ApiTokenPruefGrenzen(new ApiTokenPruefGrenzen.Einstellung(10, 2, 2), uhr);
        grenzen.pruefen(Optional.of("192.0.2.1"));
        grenzen.pruefen(Optional.of("192.0.2.2"));

        uhr.weiter(Duration.ofSeconds(6));

        assertThat(wartezeit(grenzen, "192.0.2.3")).isZero();
        assertThat(grenzen.quellen()).isEqualTo(1);
    }
}
