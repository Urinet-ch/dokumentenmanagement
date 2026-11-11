package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Dokumente suchen (A2–A4, Req 14.1–14.5, 14.7). Die REST-Schicht prüft das Recht; hier bestimmt die
 * {@link Sichtregel} des Benutzers, welche Dokumente Treffer, Zähler und Seiten enthalten. Die Suche wird nicht
 * protokolliert (design.md «Protokoll und Manifest»), und der Suchbegriff kommt nie ins Log.
 */
@ApplicationScoped
@Transactional
public class DokumentSuche {

    private final SuchePort port;

    public DokumentSuche(SuchePort port) {
        this.port = port;
    }

    public Suchergebnis suchen(Benutzer benutzer, Suchanfrage anfrage) {
        return port.suchen(anfrage, Sichtregel.fuer(benutzer));
    }
}
