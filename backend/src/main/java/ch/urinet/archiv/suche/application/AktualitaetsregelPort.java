package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.suche.domain.Aktualitaetsregel;

/** Driven Port für die Aktualitätsregel des Mandanten der laufenden Anfrage. */
public interface AktualitaetsregelPort {

    Aktualitaetsregel fuer(String mandantCode);
}
