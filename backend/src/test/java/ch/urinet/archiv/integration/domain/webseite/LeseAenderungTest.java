package ch.urinet.archiv.integration.domain.webseite;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe.MITGLIEDER;
import static ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe.OEFFENTLICH;
import static ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe.VORSTAND;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Art und Stufe eines Eintrags des Lese-Feeds (Spec 03, Aufgabe 11.9, Req 14.1): die tiefere der sichtbaren Stufen vor
 * und nach der Änderung, nie über dem Schlüssel; über dem Schlüssel gilt ein Dokument als nicht sichtbar.
 */
class LeseAenderungTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant ZEIT = Instant.parse("2026-11-16T10:00:00Z");

    private static Optional<LeseAenderung> bewerten(Zugriffsstufe vorher, Zugriffsstufe nachher, Zugriffsstufe schluessel) {
        return LeseAenderung.bewerten(ID, vorher, nachher, schluessel, ZEIT);
    }

    private static void erwarte(Zugriffsstufe vorher, Zugriffsstufe nachher, Zugriffsstufe schluessel, AenderungArt art,
                                Zugriffsstufe stufe) {
        assertThat(bewerten(vorher, nachher, schluessel)).as("%s -> %s mit %s", vorher, nachher, schluessel)
                .contains(new LeseAenderung(ID, art, stufe, ZEIT));
    }

    @Test
    void wechselVonOeffentlichZuVorstand() {
        erwarte(OEFFENTLICH, VORSTAND, OEFFENTLICH, AenderungArt.NICHT_SICHTBAR, OEFFENTLICH);
        erwarte(OEFFENTLICH, VORSTAND, MITGLIEDER, AenderungArt.NICHT_SICHTBAR, OEFFENTLICH);
        erwarte(OEFFENTLICH, VORSTAND, VORSTAND, AenderungArt.GEAENDERT, OEFFENTLICH);
    }

    @Test
    void wechselVonMitgliederZuVorstand() {
        assertThat(bewerten(MITGLIEDER, VORSTAND, OEFFENTLICH)).isEmpty();
        erwarte(MITGLIEDER, VORSTAND, MITGLIEDER, AenderungArt.NICHT_SICHTBAR, MITGLIEDER);
        erwarte(MITGLIEDER, VORSTAND, VORSTAND, AenderungArt.GEAENDERT, MITGLIEDER);
    }

    @Test
    void wechselNachUnten() {
        erwarte(VORSTAND, OEFFENTLICH, OEFFENTLICH, AenderungArt.SICHTBAR, OEFFENTLICH);
        erwarte(VORSTAND, OEFFENTLICH, VORSTAND, AenderungArt.GEAENDERT, OEFFENTLICH);
        erwarte(VORSTAND, MITGLIEDER, MITGLIEDER, AenderungArt.SICHTBAR, MITGLIEDER);
        assertThat(bewerten(VORSTAND, MITGLIEDER, OEFFENTLICH)).isEmpty();
    }

    @Test
    void erstesVeroeffentlichenUndRueckzug() {
        for (Zugriffsstufe schluessel : Zugriffsstufe.values()) {
            erwarte(null, OEFFENTLICH, schluessel, AenderungArt.SICHTBAR, OEFFENTLICH);
            erwarte(OEFFENTLICH, null, schluessel, AenderungArt.NICHT_SICHTBAR, OEFFENTLICH);
        }
        erwarte(null, VORSTAND, VORSTAND, AenderungArt.SICHTBAR, VORSTAND);
        erwarte(VORSTAND, null, VORSTAND, AenderungArt.NICHT_SICHTBAR, VORSTAND);
        assertThat(bewerten(null, VORSTAND, MITGLIEDER)).isEmpty();
        assertThat(bewerten(VORSTAND, null, MITGLIEDER)).isEmpty();
    }

    @Test
    void unsichtbarVorUndNachherGehoertNichtInDenFeed() {
        for (Zugriffsstufe schluessel : Zugriffsstufe.values()) {
            assertThat(bewerten(null, null, schluessel)).isEmpty();
        }
    }

    @Test
    void dieStufeLiegtNieUeberDemSchluessel() {
        for (Zugriffsstufe schluessel : Zugriffsstufe.values()) {
            for (Zugriffsstufe vorher : new Zugriffsstufe[]{null, OEFFENTLICH, MITGLIEDER, VORSTAND}) {
                for (Zugriffsstufe nachher : new Zugriffsstufe[]{null, OEFFENTLICH, MITGLIEDER, VORSTAND}) {
                    bewerten(vorher, nachher, schluessel)
                            .ifPresent(a -> assertThat(a.stufe().hoeherAls(schluessel)).isFalse());
                }
            }
        }
    }
}
