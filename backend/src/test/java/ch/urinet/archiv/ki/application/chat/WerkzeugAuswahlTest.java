package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 02, Req 3.1, 4.2, 5.1, 5.2: Auswahl der Werkzeuge und Kontext der Chat-Anfrage. */
class WerkzeugAuswahlTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    @Test
    void vollstaendigkeitFehltFuerRollenOhneA3UndA4() {
        WerkzeugAuswahl auswahl = new WerkzeugAuswahl(m -> false, m -> true);
        for (Rolle rolle : Rolle.values()) {
            WerkzeugKontext kontext = WerkzeugKontext.fuer(URINET, new Benutzer("b", rolle), null);
            // aus der Rechtematrix: A3 und A4 ohne Einschränkung auf eigene Dokumente
            boolean a3A4 = java.util.stream.Stream.of(Aktion.A3, Aktion.A4)
                    .allMatch(a -> Set.of(Ergebnis.ERLAUBT, Ergebnis.NUR_LESEND).contains(Rechtematrix.ergebnis(rolle, a)));
            assertThat(auswahl.angeboten(kontext)).as(rolle.name()).startsWith("dokumente_suchen", "dokument_zeigen",
                    "fristen");
            assertThat(auswahl.erlaubt(kontext, "vollstaendigkeit")).as(rolle.name()).isEqualTo(a3A4);
            assertThat(auswahl.angeboten(kontext)).as(rolle.name()).hasSize(a3A4 ? 6 : 5)
                    .contains("wissen_index", "wissen_seite_zeigen");
            assertThat(auswahl.erlaubt(kontext, "kennzahl")).as(rolle.name()).isFalse();
        }
    }

    /** Spec 04, Requirement 21.2: Im Wissenschat gibt es nur die beiden Wissenswerkzeuge, für jede Rolle und jeden Mandanten. */
    @Test
    void wissensmodusBietetNurDieWissenswerkzeuge() {
        WerkzeugAuswahl auswahl = new WerkzeugAuswahl(m -> true, m -> true);
        for (Rolle rolle : Rolle.values()) {
            Benutzer benutzer = new Benutzer("b", rolle);
            WerkzeugKontext wissen = new WerkzeugKontext(URINET, benutzer, rolle, Sichtregel.fuer(benutzer),
                    java.util.Optional.empty(), ch.urinet.archiv.ki.domain.chat.Chatmodus.WISSEN);
            assertThat(auswahl.angeboten(wissen)).as(rolle.name()).containsExactly("wissen_index", "wissen_seite_zeigen");
            for (String archiv : new String[] {"dokumente_suchen", "dokument_zeigen", "fristen", "kennzahl",
                    "offene_posten", "vollstaendigkeit"}) {
                assertThat(auswahl.erlaubt(wissen, archiv)).as(rolle + " " + archiv).isFalse();
            }
            assertThat(auswahl.erlaubt(wissen, "wissen_index")).isTrue();
            // der Archivchat bleibt unverändert
            assertThat(auswahl.angeboten(WerkzeugKontext.fuer(URINET, benutzer, null))).contains("dokumente_suchen",
                    "dokument_zeigen", "fristen", "wissen_index", "wissen_seite_zeigen");
        }
    }

    /** Req 3.1 und Spec 05, Req 8.8: kennzahl und offene_posten nur mit Finanzfunktionen des Mandanten. */
    @Test
    void finanzwerkzeugeNurMitFinanzfunktionen() {
        MandantCode ohne = new MandantCode("ohne-finanz");
        WerkzeugAuswahl auswahl = new WerkzeugAuswahl(m -> URINET.equals(m), m -> true);
        for (Rolle rolle : Rolle.values()) {
            assertThat(auswahl.angeboten(WerkzeugKontext.fuer(URINET, new Benutzer("b", rolle), null))).as(rolle.name())
                    .startsWith("dokumente_suchen", "dokument_zeigen", "fristen", "kennzahl", "offene_posten");
            WerkzeugKontext fremd = WerkzeugKontext.fuer(ohne, new Benutzer("b", rolle), null);
            assertThat(auswahl.angeboten(fremd)).as(rolle.name()).doesNotContain("kennzahl", "offene_posten");
            assertThat(auswahl.erlaubt(fremd, "kennzahl")).isFalse();
            assertThat(auswahl.erlaubt(fremd, "offene_posten")).isFalse();
        }
    }

    @Test
    void scheitertDieFrageNachFinanzfunktionenGibtEsDieWerkzeugeNicht() {
        WerkzeugAuswahl auswahl = new WerkzeugAuswahl(m -> {
            throw new IllegalStateException("Datenbank weg");
        }, m -> true);
        assertThat(auswahl.angeboten(WerkzeugKontext.fuer(URINET, new Benutzer("b", Rolle.INHABER), null)))
                .containsExactly("dokumente_suchen", "dokument_zeigen", "fristen", "vollstaendigkeit",
                        "wissen_index", "wissen_seite_zeigen");
    }

    /** Spec 04, Req 22.7: ohne Freischaltung weder im Archivchat noch im Wissenschat Wissenswerkzeuge. */
    @Test
    @Tag("req-22.7")
    void ohneFreischaltungKeineWissenswerkzeuge() {
        WerkzeugAuswahl auswahl = new WerkzeugAuswahl(m -> true, m -> false);
        WerkzeugKontext archiv = WerkzeugKontext.fuer(URINET, new Benutzer("b", Rolle.INHABER), null);
        assertThat(auswahl.angeboten(archiv)).doesNotContain("wissen_index", "wissen_seite_zeigen")
                .contains("dokumente_suchen");
        assertThat(auswahl.erlaubt(archiv, "wissen_index")).isFalse();
        assertThat(auswahl.erlaubt(archiv, "wissen_seite_zeigen")).isFalse();
        WerkzeugAuswahl scheitert = new WerkzeugAuswahl(m -> true, m -> {
            throw new IllegalStateException("Datenbank weg");
        });
        assertThat(scheitert.angeboten(archiv)).as("fail closed").doesNotContain("wissen_index");
    }

    /** Spec 05, Req 5.4: nur Rollen, die A3 und A4 uneingeschränkt sehen. */
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
