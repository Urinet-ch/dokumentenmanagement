package ch.urinet.archiv.dokument.application;

import java.util.List;

/** Eine Seite einer Liste mit der Gesamtzahl der für den Benutzer sichtbaren Einträge (Req 2.3: Zähler ohne Lücken). */
public record Seite<T>(List<T> eintraege, long gesamt) {

    public Seite {
        eintraege = List.copyOf(eintraege);
    }
}
