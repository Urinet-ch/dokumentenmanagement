package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.integration.application.webseite.WebseitenGrenzen.Einstellung;
import ch.urinet.archiv.integration.domain.webseite.Grenzklasse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 03, Req 15.1: Grenzen je Schlüssel (Lesen, Suche, Erfassung, Uploads), Speicherbegrenzung, Fehlerverhalten. */
class WebseitenGrenzenTest {

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

    private static final class Kennzahl implements WebseitenGrenzenKennzahl {
        final List<ZuVieleAnfragen.Grund> abgelehnt = new ArrayList<>();
        int ausgefallen;

        @Override
        public void abgelehnt(ZuVieleAnfragen.Grund grund) {
            abgelehnt.add(grund);
        }

        @Override
        public void ausgefallen() {
            ausgefallen++;
        }
    }

    private final Uhr uhr = new Uhr();
    private final Kennzahl kennzahl = new Kennzahl();

    /** Lesen 1/s Spitze 5, Suche 0.5/s Spitze 2, Erfassung 1/s Spitze 3, Uploads 2 je Stunde, höchstens 3 Schlüssel. */
    private WebseitenGrenzen grenzen() {
        return new WebseitenGrenzen(new Einstellung(1, 5, 0.5, 2, 1, 3, 2, 3), uhr, kennzahl);
    }

    @Test
    void pruefdateienHabenEngereSpitzeUndZaehlenAuchImErfassungsbucket() {
        WebseitenGrenzen g = grenzen();
        g.pruefen("token:e", Grenzklasse.DATEI);
        g.pruefen("token:e", Grenzklasse.DATEI);
        assertThat(abgelehnt(g, "token:e", Grenzklasse.DATEI)).isNotNull();
        g.pruefen("token:e", Grenzklasse.ERFASSUNG);
        assertThat(abgelehnt(g, "token:e", Grenzklasse.ERFASSUNG)).isNotNull();
        g.pruefen("token:anderer", Grenzklasse.DATEI);
        uhr.vor(Duration.ofSeconds(1));
        g.pruefen("token:e", Grenzklasse.DATEI);
        assertThat(abgelehnt(g, "token:e", Grenzklasse.DATEI)).isNotNull();
    }

    private static ZuVieleAnfragen abgelehnt(WebseitenGrenzen g, String id, Grenzklasse klasse) {
        try {
            g.pruefen(id, klasse);
        } catch (ZuVieleAnfragen e) {
            return e;
        }
        return null;
    }

    @Test
    void dieSpitzeErlaubtEinenStossDanachGiltDieRateMitRetryAfter() {
        WebseitenGrenzen g = grenzen();
        for (int i = 0; i < 5; i++) {
            g.pruefen("token:a", Grenzklasse.LESEN);
        }
        assertThatThrownBy(() -> g.pruefen("token:a", Grenzklasse.LESEN)).isInstanceOfSatisfying(ZuVieleAnfragen.class, e -> {
            assertThat(e.code()).isEqualTo(Fehlercode.ZU_VIELE_ANFRAGEN);
            assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.ANFRAGEN);
            assertThat(e.wartezeitSekunden()).isEqualTo(1);
        });
        uhr.vor(Duration.ofSeconds(1));
        g.pruefen("token:a", Grenzklasse.LESEN);
        assertThat(abgelehnt(g, "token:a", Grenzklasse.LESEN)).isNotNull();
        assertThat(kennzahl.abgelehnt).containsExactly(ZuVieleAnfragen.Grund.ANFRAGEN, ZuVieleAnfragen.Grund.ANFRAGEN);
    }

    @Test
    void jederSchluesselHatSeinenEigenenBucket() {
        WebseitenGrenzen g = grenzen();
        for (int i = 0; i < 5; i++) {
            g.pruefen("token:a", Grenzklasse.LESEN);
        }
        assertThat(abgelehnt(g, "token:a", Grenzklasse.LESEN)).isNotNull();
        g.pruefen("token:b", Grenzklasse.LESEN);
    }

    @Test
    void dieSucheIstStrengerZaehltImLesenBucketMitUndEineAbgelehnteKostetNichts() {
        WebseitenGrenzen g = grenzen();
        g.pruefen("token:a", Grenzklasse.SUCHE);
        g.pruefen("token:a", Grenzklasse.SUCHE);
        ZuVieleAnfragen e = abgelehnt(g, "token:a", Grenzklasse.SUCHE);
        assertThat(e).isNotNull();
        assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.SUCHE);
        assertThat(e.wartezeitSekunden()).isEqualTo(2);
        // zwei Marken im Lesen-Bucket sind für die Suchen weg, die abgelehnte dritte hat keine genommen: 3 von 5 frei
        for (int i = 0; i < 3; i++) {
            g.pruefen("token:a", Grenzklasse.LESEN);
        }
        assertThat(abgelehnt(g, "token:a", Grenzklasse.LESEN)).isNotNull();
    }

    @Test
    void istDerLesenBucketLeerGiltSeineWartezeitAuchFuerDieSuche() {
        WebseitenGrenzen g = grenzen();
        for (int i = 0; i < 5; i++) {
            g.pruefen("token:a", Grenzklasse.LESEN);
        }
        ZuVieleAnfragen e = abgelehnt(g, "token:a", Grenzklasse.SUCHE);
        assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.ANFRAGEN);
    }

    @Test
    void uploadsSindProStundeBegrenztUndZaehlenImErfassungBucketMit() {
        WebseitenGrenzen g = grenzen();
        g.pruefen("token:e", Grenzklasse.UPLOAD);
        g.pruefen("token:e", Grenzklasse.UPLOAD);
        ZuVieleAnfragen e = abgelehnt(g, "token:e", Grenzklasse.UPLOAD);
        assertThat(e.grund()).isEqualTo(ZuVieleAnfragen.Grund.UPLOADS);
        // 2 je Stunde: die nächste Marke nach 30 Minuten
        assertThat(e.wartezeitSekunden()).isEqualTo(1800);
        // ein Erfassungs-Lesen geht noch (3 Spitze, 2 genommen)
        g.pruefen("token:e", Grenzklasse.ERFASSUNG);
        uhr.vor(Duration.ofMinutes(30));
        g.pruefen("token:e", Grenzklasse.UPLOAD);
    }

    @Test
    void dieWartezeitIstAufEineStundeBegrenzt() {
        WebseitenGrenzen g = new WebseitenGrenzen(new Einstellung(1, 5, 0.5, 2, 1, 3, 1, 3), uhr, kennzahl);
        g.pruefen("token:e", Grenzklasse.UPLOAD);
        assertThat(abgelehnt(g, "token:e", Grenzklasse.UPLOAD).wartezeitSekunden()).isEqualTo(3600);
    }

    @Test
    void derSpeicherIstBegrenztUeberzaehligeSchluesselTeilenSichEinenSammelEintrag() {
        WebseitenGrenzen g = grenzen();
        for (String id : List.of("token:1", "token:2", "token:3")) {
            g.pruefen(id, Grenzklasse.LESEN);
        }
        assertThat(g.eintraege()).isEqualTo(3);
        // Tabelle voll mit aktiven Schlüsseln: der vierte und fünfte teilen sich den Sammel-Eintrag
        for (int i = 0; i < 5; i++) {
            g.pruefen("token:4", Grenzklasse.LESEN);
        }
        assertThat(abgelehnt(g, "token:5", Grenzklasse.LESEN)).isNotNull();
        assertThat(g.eintraege()).isEqualTo(3);
    }

    @Test
    void eintraegeImLeerlaufWerdenBeiVollerTabelleVerworfen() {
        WebseitenGrenzen g = grenzen();
        for (String id : List.of("token:1", "token:2", "token:3")) {
            g.pruefen(id, Grenzklasse.LESEN);
        }
        uhr.vor(Duration.ofMinutes(5));
        g.pruefen("token:4", Grenzklasse.LESEN);
        assertThat(g.eintraege()).isEqualTo(1);
    }

    @Test
    void einFehlerDerPruefungLaesstDieAnfrageDurchUndZaehltInDerKennzahl() {
        WebseitenGrenzen g = new WebseitenGrenzen(new Einstellung(1, 5, 0.5, 2, 1, 3, 2, 3), uhr, kennzahl);
        g.pruefen(null, Grenzklasse.LESEN);
        g.pruefen("token:a", null);
        assertThat(kennzahl.ausgefallen).isEqualTo(2);
    }

    @Test
    void ungueltigeEinstellungBrichtDenStartAbOhneWerteZuNennen() {
        assertThatThrownBy(() -> new WebseitenGrenzen(new Einstellung(0, 5, 0.5, 2, 1, 3, 2, 3), uhr, kennzahl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("0.5");
        assertThatThrownBy(() -> new Einstellung(1, 5, 1, 2, 1, 3, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
