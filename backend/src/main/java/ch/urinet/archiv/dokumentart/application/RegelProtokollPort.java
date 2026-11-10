package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;

/**
 * Hält eine Änderung der Aufbewahrungsregel einer Dokumentart (Jahre, «ab», gesetzliche Pflicht) mit alter und neuer
 * Regel im Zugriffsprotokoll fest (Req 9.10, entschieden von Fabian am 10.11.2026). Schreibt in der Transaktion der
 * Änderung; scheitert er, wird die Änderung zurückgerollt (Req 10.8).
 */
public interface RegelProtokollPort {

    void regelGeaendert(Dokumentart bisher, Dokumentart neu);
}
