package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;

/** Messpunkt der Grenzen je Schlüssel (Spec 03, 11.11): nur feste Gründe, nie Schlüssel-ID oder Webseite. */
public interface WebseitenGrenzenKennzahl {

    void abgelehnt(ZuVieleAnfragen.Grund grund);

    /** Die Prüfung selbst ist ausgefallen; die Anfrage wurde durchgelassen. */
    void ausgefallen();
}
