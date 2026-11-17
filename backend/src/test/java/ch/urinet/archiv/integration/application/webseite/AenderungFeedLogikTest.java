package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort.Horizont;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort.Lesung;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort.Zeile;
import ch.urinet.archiv.integration.domain.webseite.AenderungArt;
import ch.urinet.archiv.integration.domain.webseite.FeedCursor;
import ch.urinet.archiv.integration.domain.webseite.FeedSeite;
import ch.urinet.archiv.integration.domain.webseite.LeseAenderung;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ablauf des Feeds ohne Datenbank (Spec 03, Aufgabe 11.9, Req 14.1, 14.3, 14.4): Cursor, Seitengrösse, 410 und
 * 400 bei einem Cursor aus der Zukunft. Der Port ist eine Liste mit festem Horizont.
 */
class AenderungFeedLogikTest {

    private static final Instant ZEIT = Instant.parse("2026-11-16T10:00:00Z");

    private static final class Port implements AenderungFeedPort {
        final List<Zeile> zeilen = new ArrayList<>();
        long horizont = 100;
        long naechste = 110;
        Long bereinigt;

        @Override
        public Lesung lesen(FeedCursor ab, int max, List<String> stufen) {
            return lesung(ab, max, z -> stufen.contains(z.vorher()) || stufen.contains(z.nachher()));
        }

        @Override
        public Lesung lesenErfassung(FeedCursor ab, int max, String webseite) {
            return lesung(ab, max, z -> true);
        }

        private Lesung lesung(FeedCursor ab, int max, java.util.function.Predicate<Zeile> filter) {
            List<Zeile> treffer = zeilen.stream().filter(z -> z.xid() < horizont)
                    .filter(z -> z.xid() > ab.xid() || (z.xid() == ab.xid() && z.id() > ab.id())).filter(filter)
                    .sorted(java.util.Comparator.comparingLong(Zeile::xid).thenComparingLong(Zeile::id)).limit(max)
                    .toList();
            return new Lesung(treffer, horizont, naechste);
        }

        @Override
        public Optional<Long> bereinigtBis() {
            return Optional.ofNullable(bereinigt);
        }

        @Override
        public int bereinigen(Instant grenze) {
            return 0;
        }

        @Override
        public Horizont horizont() {
            return new Horizont(horizont, naechste);
        }
    }

    private final Port port = new Port();
    private final AenderungFeed feed = new AenderungFeed(port);
    private final Lesezugriff vorstand = new Lesezugriff("w", Zugriffsstufe.VORSTAND, Zugriffsstufe.OEFFENTLICH,
            Optional.empty());

    private void eintrag(long xid, long id, String vorher, String nachher) {
        port.zeilen.add(new Zeile(xid, id, UUID.randomUUID(), ZEIT, "ARCHIVIERT", 1, vorher, nachher, null, null));
    }

    @Test
    void ohneCursorKommtNurDerHorizont() {
        eintrag(50, 1, null, "oeffentlich");

        FeedSeite<LeseAenderung> seite = feed.lesen(vorstand, Optional.empty(), 100);

        assertThat(seite.eintraege()).isEmpty();
        assertThat(seite.cursor()).isEqualTo(FeedCursor.vor(100));
    }

    @Test
    void esGiltDieStufeDesSchluesselsNichtDieGemeldete() {
        eintrag(50, 1, "oeffentlich", "vorstand");

        FeedSeite<LeseAenderung> seite = feed.lesen(vorstand, Optional.of(FeedCursor.vor(1).kodieren()), 100);

        assertThat(seite.eintraege()).singleElement().satisfies(a -> {
            assertThat(a.art()).isEqualTo(AenderungArt.GEAENDERT);
            assertThat(a.stufe()).isEqualTo(Zugriffsstufe.OEFFENTLICH);
        });
    }

    @Test
    void volleSeiteEndetBeimLetztenEintragDieRestlicheBeimHorizont() {
        for (int i = 1; i <= 5; i++) {
            eintrag(10 + i, i, "oeffentlich", "oeffentlich");
        }
        String start = FeedCursor.vor(1).kodieren();

        FeedSeite<LeseAenderung> eins = feed.lesen(vorstand, Optional.of(start), 2);
        assertThat(eins.eintraege()).hasSize(2);
        assertThat(eins.cursor()).isEqualTo(new FeedCursor(12, 2));

        FeedSeite<LeseAenderung> zwei = feed.lesen(vorstand, Optional.of(eins.cursor().kodieren()), 2);
        assertThat(zwei.eintraege()).hasSize(2);
        assertThat(zwei.cursor()).isEqualTo(new FeedCursor(14, 4));

        FeedSeite<LeseAenderung> drei = feed.lesen(vorstand, Optional.of(zwei.cursor().kodieren()), 2);
        assertThat(drei.eintraege()).hasSize(1);
        assertThat(drei.cursor()).isEqualTo(FeedCursor.vor(100));

        FeedSeite<LeseAenderung> leer = feed.lesen(vorstand, Optional.of(drei.cursor().kodieren()), 2);
        assertThat(leer.eintraege()).isEmpty();
        assertThat(leer.cursor()).isEqualTo(drei.cursor());
    }

    @Test
    void einEintragEinerOffenenTransaktionKommtErstNachDemHorizont() {
        eintrag(50, 1, null, "oeffentlich");
        eintrag(120, 2, null, "oeffentlich");
        String start = FeedCursor.vor(1).kodieren();

        FeedSeite<LeseAenderung> vorher = feed.lesen(vorstand, Optional.of(start), 100);
        assertThat(vorher.eintraege()).hasSize(1);
        assertThat(vorher.cursor()).isEqualTo(FeedCursor.vor(100));

        port.horizont = 130;
        port.naechste = 131;
        FeedSeite<LeseAenderung> danach = feed.lesen(vorstand, Optional.of(vorher.cursor().kodieren()), 100);
        assertThat(danach.eintraege()).hasSize(1);
    }

    @Test
    void derCursorRuecktNieZurueck() {
        FeedCursor weit = new FeedCursor(105, 0);

        FeedSeite<LeseAenderung> seite = feed.lesen(vorstand, Optional.of(weit.kodieren()), 100);

        assertThat(seite.cursor()).isEqualTo(weit);
    }

    @Test
    void einCursorUnterDerBereinigungIst410() {
        port.bereinigt = 40L;

        assertThatThrownBy(() -> feed.lesen(vorstand, Optional.of(new FeedCursor(40, 5).kodieren()), 100))
                .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.CURSOR_ABGELAUFEN));
        assertThatThrownBy(() -> feed.erfassung("w", Optional.of(new FeedCursor(3, 0).kodieren()), 100))
                .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.CURSOR_ABGELAUFEN));
        assertThat(feed.lesen(vorstand, Optional.of(new FeedCursor(41, 0).kodieren()), 100).eintraege()).isEmpty();
        // ohne Cursor gibt es nichts abzulaufen
        assertThat(feed.lesen(vorstand, Optional.empty(), 100).cursor()).isEqualTo(FeedCursor.vor(100));
    }

    @Test
    void einCursorAusDerZukunftIst400() {
        assertThatThrownBy(() -> feed.lesen(vorstand, Optional.of(new FeedCursor(111, 0).kodieren()), 100))
                .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        assertThat(feed.lesen(vorstand, Optional.of(new FeedCursor(110, 0).kodieren()), 100).eintraege()).isEmpty();
    }

    @Test
    void maxIstStandard100HoechstensFuenfhundert() {
        assertThat(AenderungFeed.max(null)).isEqualTo(100);
        assertThat(AenderungFeed.max(1)).isEqualTo(1);
        assertThat(AenderungFeed.max(500)).isEqualTo(500);
        for (int ungueltig : new int[]{0, -1, 501, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> AenderungFeed.max(ungueltig)).as("max=%d", ungueltig)
                    .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
    }

    @Test
    void derErfassungsFeedNenntAllesOhneStufenfilter() {
        eintrag(50, 1, null, null);
        port.zeilen.add(new Zeile(51, 2, UUID.randomUUID(), ZEIT, "FEHLER", 3, null, null,
                "VERARBEITUNG_FEHLGESCHLAGEN", "R-1"));

        var seite = feed.erfassung("w", Optional.of(FeedCursor.vor(1).kodieren()), 100);

        assertThat(seite.eintraege()).hasSize(2);
        assertThat(seite.eintraege().get(1).referenz()).isEqualTo("R-1");
        assertThat(seite.eintraege().get(1).fehlercode()).isEqualTo("VERARBEITUNG_FEHLGESCHLAGEN");
        assertThat(seite.eintraege().get(1).version()).isEqualTo(3);
    }
}
