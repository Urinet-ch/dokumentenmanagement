package ch.urinet.archiv.integration.domain.webseite;

import java.util.List;

/** Eine Antwort des Feeds: die Einträge ab dem Cursor und der Cursor für den nächsten Abruf. */
public record FeedSeite<T>(List<T> eintraege, FeedCursor cursor) {
}
