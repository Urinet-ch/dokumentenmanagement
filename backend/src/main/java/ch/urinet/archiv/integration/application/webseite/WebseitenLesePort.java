package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.WebseiteDokument;
import ch.urinet.archiv.integration.domain.webseite.WebseiteReihe;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: lesende Abfragen der Webseiten-Schnittstelle im Mandanten der Anfrage (Spec 03, Aufgabe 11.2). Jede
 * Abfrage trägt den festen Filter ({@link FesterFilter}) mit den Stufen des {@link Lesezugriff}; was er nicht liefert,
 * gibt es für die Anfrage nicht.
 */
public interface WebseitenLesePort {

    /** Eine Seite ({@code seite} ab 1) der sichtbaren Dokumente ohne Auszug. */
    WebseiteSeite liste(Lesezugriff zugriff, ListenFilter filter, WebseiteSortierung sortierung, int seite,
                        int proSeite);

    /**
     * Die sichtbaren unter den Dokumenten in der Reihenfolge von {@code ids}, mit Auszug zum Suchbegriff; nicht
     * sichtbare fehlen.
     */
    List<WebseiteDokument> treffer(Lesezugriff zugriff, List<UUID> ids, String begriff);

    /** Ein sichtbares Dokument mit dem Anfang seines Textes als Auszug; leer, wenn es nicht sichtbar ist. */
    Optional<WebseiteDokument> dokument(Lesezugriff zugriff, UUID id);

    /** Aktive Reihen, zu denen der feste Filter mindestens ein Dokument liefert, nach Name. */
    List<WebseiteReihe> reihen(Lesezugriff zugriff);

    /** Dokument-ID zu einer alten URL (normalisiert), nur wenn das Dokument sichtbar ist. */
    Optional<UUID> umleitung(Lesezugriff zugriff, String alteUrl);
}
