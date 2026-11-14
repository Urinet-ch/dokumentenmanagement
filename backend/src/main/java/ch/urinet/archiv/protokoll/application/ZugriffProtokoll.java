package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;

import java.util.List;

/**
 * Driven Port: schreibt einen Eintrag ins Zugriffsprotokoll des Mandanten der Anfrage, in der laufenden Transaktion
 * (Req 10.2). Scheitert das Schreiben, wirft er; der Aufrufer rollt die Aktion zurück (Req 10.8).
 */
public interface ZugriffProtokoll {

    void schreiben(ZugriffEintrag eintrag);

    /**
     * Schreibt mehrere Einträge in der laufenden Transaktion (z. B. ein Lauf und je Dokument ein Eintrag beim
     * Zusammenführen, Req 17.8); scheitert einer, wirft er, und der Aufrufer rollt alles zurück (Req 10.8).
     */
    default void schreiben(List<ZugriffEintrag> eintraege) {
        eintraege.forEach(this::schreiben);
    }
}
