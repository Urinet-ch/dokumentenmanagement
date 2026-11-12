package ch.urinet.archiv.suche.domain;

import java.util.List;

/** Eine Seite der Treffer und die Zahl aller für den Benutzer sichtbaren Treffer (Req 2.3: Zähler ohne Lücken). */
public record Suchergebnis(List<Suchtreffer> treffer, long gesamt) {

    public Suchergebnis {
        treffer = List.copyOf(treffer);
    }
}
