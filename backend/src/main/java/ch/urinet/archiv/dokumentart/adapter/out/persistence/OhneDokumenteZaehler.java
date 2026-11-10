package ch.urinet.archiv.dokumentart.adapter.out.persistence;

import ch.urinet.archiv.dokumentart.application.DokumentZaehlerPort;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bis Aufgabe 2.2 gibt es keine Tabelle {@code dokument} und damit keine Dokumente; 2.2 ersetzt diesen Zähler durch
 * eine Abfrage, die auch verworfene Dokumente zählt (Req 9.4).
 */
@ApplicationScoped
public class OhneDokumenteZaehler implements DokumentZaehlerPort {

    @Override
    public long anzahl(String art) {
        return 0;
    }
}
