package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Abschnitttreffer;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchtreffer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Driven Port für die Datenbankteile der Hybridsuche im Schema des Mandanten der laufenden Anfrage (Spec 02, Req 2.2,
 * 2.5; Aufgabe 6.4). Jede Methode prüft die {@link Sichtregel} und die Filter der {@link Suchanfrage} (Spec 01,
 * Req 14.2, ohne Suchbegriff) in der Abfrage selbst über {@code dokument}, nie über den Abschnitt: Eine geänderte
 * Vertraulichkeit gilt sofort. Aufruf nur in einer kurzen Transaktion; Werte werden nur gebunden.
 */
public interface VektorSuchePort {

    /**
     * Die {@code anzahl} nächsten Abschnitte zum Vektor nach Kosinus, Sicht und Filter vor der Grenze, verdichtet auf den
     * besten Abschnitt je Dokument; aufsteigend nach Abstand.
     */
    List<Abschnitttreffer> naechste(float[] vektor, Suchanfrage anfrage, Sichtregel regel, int anzahl);

    /**
     * Texte für den Reranker zu Dokumenten nur aus dem Volltext: Kopfzeile «Titel – Art – Partner» und die ersten
     * {@code zeichen} Zeichen des Volltexts, nie der Dateiname (Spec 01, Req 3.13). Unsichtbare Dokumente fehlen.
     */
    Map<UUID, String> texte(Collection<UUID> ids, Suchanfrage anfrage, Sichtregel regel, int zeichen);

    /**
     * Felder der Treffer für Dokumente nur aus dem Vektor-Teil; der Auszug hebt den Suchbegriff im Inhalt des besten
     * Abschnitts hervor (ohne Kopfzeile und ohne Dateinamen), {@code dateiname} nur mit {@code mitDateiname}.
     * Unsichtbare Dokumente fehlen; die Reihenfolge ist offen.
     */
    List<Suchtreffer> details(List<Abschnitttreffer> abschnitte, Suchanfrage anfrage, Sichtregel regel,
                              boolean mitDateiname);
}
