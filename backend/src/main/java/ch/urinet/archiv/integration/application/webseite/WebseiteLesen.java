package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.AlteUrl;
import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.WebseiteDokument;
import ch.urinet.archiv.integration.domain.webseite.WebseiteReihe;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Lesende Endpunkte der Webseiten-Schnittstelle ohne Suche und Datei (Spec 03, Aufgabe 11.2, Req 5.1, 5.5, 5.8, 5.10,
 * 5.11): Liste, Details, Filter und Umleitung. Alles läuft über {@link WebseitenLesePort} mit dem festen Filter der
 * Stufen der Anfrage; ein Dokument, das er nicht liefert, ist 404 {@code NICHT_GEFUNDEN} und gleicht einem
 * unbekannten (kein Hinweis auf die Existenz, Req 5.5).
 */
@ApplicationScoped
@Transactional
public class WebseiteLesen {

    public static final int PRO_SEITE_STANDARD = 20;
    public static final int PRO_SEITE_MAX = 100;
    public static final int SEITE_MAX = 9_999;

    private final WebseitenLesePort port;

    public WebseiteLesen(WebseitenLesePort port) {
        this.port = port;
    }

    /** {@code seite} ab 1; ausserhalb der Grenzen 400. */
    public WebseiteSeite liste(Lesezugriff zugriff, ListenFilter filter, WebseiteSortierung sortierung, int seite,
                               int proSeite) {
        if (seite < 1 || seite > SEITE_MAX || proSeite < 1 || proSeite > PRO_SEITE_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return port.liste(zugriff, filter, sortierung, seite, proSeite);
    }

    public WebseiteDokument details(Lesezugriff zugriff, UUID id) {
        return port.dokument(zugriff, id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    public List<WebseiteReihe> reihen(Lesezugriff zugriff) {
        return port.reihen(zugriff);
    }

    /** Dokument-ID zur alten URL; ungültige URL 400, unbekannt oder nicht sichtbar 404. */
    public UUID umleitung(Lesezugriff zugriff, String url) {
        return port.umleitung(zugriff, AlteUrl.normalisieren(url))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }
}
