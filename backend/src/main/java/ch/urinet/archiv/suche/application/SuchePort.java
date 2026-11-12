package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;

/**
 * Driven Port der Suche im Schema des Mandanten der laufenden Anfrage (ADR-002, Req 1.2). Treffer, Gesamtzahl und
 * Paging entstehen in einer Abfrage unter derselben {@link Sichtregel}, damit kein Zähler ein unsichtbares Dokument
 * verrät (Req 2.3, 14.5). Werte werden nur gebunden, nie in das SQL geschrieben.
 */
public interface SuchePort {

    Suchergebnis suchen(Suchanfrage anfrage, Sichtregel regel);
}
