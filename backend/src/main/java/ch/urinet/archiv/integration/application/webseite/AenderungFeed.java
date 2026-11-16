package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort.Lesung;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedPort.Zeile;
import ch.urinet.archiv.integration.domain.webseite.ErfassungAenderung;
import ch.urinet.archiv.integration.domain.webseite.FeedCursor;
import ch.urinet.archiv.integration.domain.webseite.FeedSeite;
import ch.urinet.archiv.integration.domain.webseite.LeseAenderung;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Änderungs-Feed der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.9, Req 14.1 bis 14.4). Die Webseite fragt ab, das
 * Archiv ruft nie eine Webseite auf (Req 14.5). Der Lese-Feed gilt für die Stufe des Schlüssels (nie für eine gemeldete
 * Stufe) und nennt nur ID, Art, Stufe und Zeit; der Erfassungs-Feed nennt Status und Fehlercode der Dokumente des
 * Mandanten und die Referenz nur der eigenen Webseite. Ohne {@code seit} liefert er keine Einträge, nur den aktuellen
 * Cursor; ein Cursor unterhalb der Aufbewahrung ist 410 {@code CURSOR_ABGELAUFEN}.
 */
@ApplicationScoped
public class AenderungFeed {

    public static final int MAX_STANDARD = 100;
    public static final int MAX_GRENZE = 500;

    private final AenderungFeedPort port;

    public AenderungFeed(AenderungFeedPort port) {
        this.port = port;
    }

    /** Lese-Feed für den Schlüssel von {@code zugriff}; {@code seit} ist der Cursor der letzten Antwort oder leer. */
    public FeedSeite<LeseAenderung> lesen(Lesezugriff zugriff, Optional<String> seit, int max) {
        Zugriffsstufe hoechste = zugriff.hoechsteStufe();
        List<String> stufen = hoechste.bisUndMit();
        return abrufen(seit, max, ab -> port.lesen(ab, max, stufen),
                zeile -> LeseAenderung.bewerten(zeile.dokumentId(), stufe(zeile.vorher()), stufe(zeile.nachher()),
                        hoechste, zeile.zeit()));
    }

    /** Erfassungs-Feed für die Webseite des Erfassungs-Schlüssels. */
    public FeedSeite<ErfassungAenderung> erfassung(String webseite, Optional<String> seit, int max) {
        return abrufen(seit, max, ab -> port.lesenErfassung(ab, max, webseite),
                zeile -> Optional.of(new ErfassungAenderung(zeile.dokumentId(), zeile.referenz(), zeile.status(),
                        zeile.fehlercode(), zeile.version(), zeile.zeit())));
    }

    /** {@code max} von 1 bis {@value #MAX_GRENZE}; sonst 400. */
    public static int max(Integer wert) {
        if (wert == null) {
            return MAX_STANDARD;
        }
        if (wert < 1 || wert > MAX_GRENZE) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return wert;
    }

    private <T> FeedSeite<T> abrufen(Optional<String> seit, int max, Function<FeedCursor, Lesung> lesen,
                                     Function<Zeile, Optional<T>> umsetzen) {
        if (seit.isEmpty()) {
            // ohne Cursor nur der Horizont: die Webseite holt ihren Bestand danach und liest dann ab hier
            return new FeedSeite<>(List.of(), FeedCursor.vor(port.horizont().xmin()));
        }
        FeedCursor ab = FeedCursor.lesen(seit.get());
        Optional<Long> bereinigt = port.bereinigtBis();
        if (bereinigt.isPresent() && ab.xid() <= bereinigt.get()) {
            throw new FachFehler(Fehlercode.CURSOR_ABGELAUFEN);
        }
        Lesung lesung = lesen.apply(ab);
        if (ab.xid() > lesung.naechste()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        List<T> eintraege = lesung.zeilen().stream().map(umsetzen).flatMap(Optional::stream).toList();
        FeedCursor naechster = ab;
        if (lesung.zeilen().size() >= max) {
            naechster = lesung.zeilen().getLast().cursor();
        } else {
            // alles bis zum Horizont ist gelesen; der Cursor rückt nie zurück
            FeedCursor horizont = FeedCursor.vor(lesung.horizont());
            if (horizont.xid() > ab.xid()) {
                naechster = horizont;
            }
        }
        return new FeedSeite<>(eintraege, naechster);
    }

    private static Zugriffsstufe stufe(String code) {
        return code == null ? null : Zugriffsstufe.ausCode(code).orElse(null);
    }
}
