package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;

/**
 * Driven Port: schreibt einen Eintrag ins Zugriffsprotokoll des Mandanten der Anfrage, in der laufenden Transaktion
 * (Req 10.2). Scheitert das Schreiben, wirft er; der Aufrufer rollt die Aktion zurück (Req 10.8).
 */
public interface ZugriffProtokoll {

    void schreiben(ZugriffEintrag eintrag);
}
