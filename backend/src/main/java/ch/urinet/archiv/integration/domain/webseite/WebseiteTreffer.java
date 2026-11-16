package ch.urinet.archiv.integration.domain.webseite;

/** Treffer der Suche: Dokument mit Auszug, Fundstelle (PDF-Seite ab 1, {@code null} wenn unbekannt) und Rang ab 1. */
public record WebseiteTreffer(WebseiteDokument dokument, Integer fundstelleSeite, int rang) {
}
