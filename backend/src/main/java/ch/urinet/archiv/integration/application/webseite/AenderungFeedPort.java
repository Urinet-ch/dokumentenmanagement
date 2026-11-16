package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.integration.domain.webseite.FeedCursor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: Tabelle {@code webseite_aenderung} im Mandanten der Anfrage bzw. des Kontexts (Spec 03, Aufgabe 11.9,
 * Req 14). Der Trigger auf {@code dokument} befüllt sie, hier wird sie nur gelesen und bereinigt. Eine Lesung liefert
 * nur Einträge unterhalb des Horizonts {@code pg_snapshot_xmin} (Req 14.3) und nennt diesen Horizont aus demselben
 * Schnappschuss wie die Einträge.
 */
public interface AenderungFeedPort {

    /**
     * Eintrag der Tabelle; {@code vorher} und {@code nachher} sind die öffentliche Stufe oder {@code null},
     * {@code referenz} ist nur beim Erfassungs-Feed gefüllt.
     */
    record Zeile(long xid, long id, UUID dokumentId, Instant zeit, String status, Integer version, String vorher,
                 String nachher, String fehlercode, String referenz) {

        public FeedCursor cursor() {
            return new FeedCursor(xid, id);
        }
    }

    /**
     * @param horizont erste Transaktions-ID, die noch offen sein kann; alles darunter ist abgeschlossen
     * @param naechste nächste noch nicht vergebene Transaktions-ID (Obergrenze jedes gültigen Cursors)
     */
    record Lesung(List<Zeile> zeilen, long horizont, long naechste) {
    }

    /** Horizont und nächste Transaktions-ID, ohne Mandanten-Tabelle (für die Überwachung). */
    record Horizont(long xmin, long xmax) {
    }

    /**
     * Einträge nach {@code ab} bis zum Horizont, nach {@code (xid, id)} sortiert, höchstens {@code max}; nur die, bei
     * denen {@code stufe_vorher} oder {@code stufe_nachher} in {@code stufen} liegt.
     */
    Lesung lesen(FeedCursor ab, int max, List<String> stufen);

    /** Alle Einträge ab {@code ab} bis zum Horizont, mit der Referenz der Webseite {@code webseite} (oder ohne). */
    Lesung lesenErfassung(FeedCursor ab, int max, String webseite);

    /** Stand der Bereinigung: Cursor mit {@code xid} bis hierher sind abgelaufen; leer, wenn nie bereinigt wurde. */
    Optional<Long> bereinigtBis();

    /** Löscht Einträge mit {@code zeit < grenze} und setzt {@code bereinigt_bis}; liefert die Anzahl. */
    int bereinigen(Instant grenze);

    Horizont horizont();
}
