package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.ProtokollFilter;
import ch.urinet.archiv.protokoll.domain.ProtokollZeile;

/** Driven Port: liest das Zugriffsprotokoll des Mandanten der Anfrage für die Einsicht (Req 10.5). */
public interface ProtokollAbfrage {

    /** Treffer des Filters, neueste zuerst, mit Name und Rolle aus dem Benutzerverzeichnis zur Zeit des Eintrags. */
    ProtokollZeile.Seite suchen(ProtokollFilter filter);
}
