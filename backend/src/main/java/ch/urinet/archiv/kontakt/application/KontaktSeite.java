package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.kontakt.domain.Kontakt;

import java.util.List;

/** Eine Seite der Kontaktliste mit der Gesamtzahl der Treffer (Req 17.1). */
public record KontaktSeite(long gesamt, List<Kontakt> eintraege) {

    public KontaktSeite {
        eintraege = List.copyOf(eintraege);
    }
}
