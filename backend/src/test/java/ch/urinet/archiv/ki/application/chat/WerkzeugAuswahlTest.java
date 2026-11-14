package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 02, Req 3.1, 4.2, 5.1, 5.2: Auswahl der Werkzeuge und Kontext der Chat-Anfrage. */
class WerkzeugAuswahlTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    @Test
    void alleRollenErhaltenSuchenZeigenUndFristen() {
        WerkzeugAuswahl auswahl = new WerkzeugAuswahl();
        for (Rolle rolle : Rolle.values()) {
            WerkzeugKontext kontext = WerkzeugKontext.fuer(URINET, new Benutzer("b", rolle), null);
            assertThat(auswahl.angeboten(kontext)).as(rolle.name())
                    .containsExactly("dokumente_suchen", "dokument_zeigen", "fristen");
            assertThat(auswahl.erlaubt(kontext, "kennzahl")).as(rolle.name()).isFalse();
            assertThat(auswahl.erlaubt(kontext, "vollstaendigkeit")).as(rolle.name()).isFalse();
        }
    }

    /** Erweiterungspunkt 7.1c: nur Rollen, die A3 und A4 uneingeschränkt sehen (Spec 05, Req 5.4). */
    @Test
    void vollstaendigkeitNurMitA3UndA4Uneingeschraenkt() {
        assertThat(WerkzeugAuswahl.vollstaendigkeitErlaubt(sicht(Rolle.INHABER))).isTrue();
        assertThat(WerkzeugAuswahl.vollstaendigkeitErlaubt(sicht(Rolle.TREUHAENDER))).isTrue();
        assertThat(WerkzeugAuswahl.vollstaendigkeitErlaubt(sicht(Rolle.GESCHAEFTSSTELLE))).isTrue();
        assertThat(WerkzeugAuswahl.vollstaendigkeitErlaubt(sicht(Rolle.MITARBEITER))).isFalse();
    }

    @Test
    void kontextPasstZumBenutzer() {
        Benutzer joel = new Benutzer("joel", Rolle.MITARBEITER);
        assertThatThrownBy(() -> new WerkzeugKontext(URINET, joel, Rolle.INHABER, Sichtregel.fuer(joel),
                java.util.Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WerkzeugKontext(URINET, joel, Rolle.MITARBEITER,
                Sichtregel.fuer(new Benutzer("joel", Rolle.INHABER)), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WerkzeugKontext.fuer(URINET, joel, "203.0.113.7").ip()).hasValueSatisfying(
                ip -> assertThat(ip.text()).isEqualTo("203.0.113.7"));
        assertThat(WerkzeugKontext.fuer(URINET, joel, "kein-ip").ip()).isEmpty();
    }

    @Test
    void registerEntferntUndLaesstAblaufen() {
        Instant[] jetzt = {Instant.parse("2026-11-14T10:00:00Z")};
        Clock uhr = new Clock() {
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
                return jetzt[0];
            }
        };
        WerkzeugKontextRegister register = new WerkzeugKontextRegister(uhr);
        WerkzeugKontext kontext = WerkzeugKontext.fuer(URINET, new Benutzer("fabian", Rolle.INHABER), null);
        register.festhalten("lauf-1", kontext);
        register.festhalten("lauf-2", kontext);
        assertThat(register.finden("lauf-1")).contains(kontext);
        assertThat(register.finden("lauf-3")).isEmpty();
        assertThat(register.finden(null)).isEmpty();
        register.entfernen("lauf-1");
        assertThat(register.finden("lauf-1")).isEmpty();
        jetzt[0] = jetzt[0].plus(WerkzeugKontextRegister.ABLAUF).minus(Duration.ofSeconds(1));
        assertThat(register.finden("lauf-2")).contains(kontext);
        jetzt[0] = jetzt[0].plus(Duration.ofSeconds(1));
        assertThat(register.finden("lauf-2")).as("abgelaufen gilt als fehlend").isEmpty();
    }

    private static Sichtregel sicht(Rolle rolle) {
        return Sichtregel.fuer(new Benutzer("b", rolle));
    }
}
