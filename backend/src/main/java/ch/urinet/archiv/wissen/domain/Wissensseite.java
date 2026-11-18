package ch.urinet.archiv.wissen.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Wissensseite(UUID id, String pfad, Seitentyp typ, String titel, String beschreibung, List<String> tags,
                           Status status, LocalDate veraltetAb, List<Quelleneintrag> quellen,
                           Vertraulichkeit vertraulichkeit, String inhalt, int version, String erzeugtVon,
                           Instant erzeugtAm, String freigegebenVon, Instant freigegebenAm, boolean quellePruefen,
                           boolean widerspruchOffen, UUID wissensquelleId) {

    public Wissensseite {
        tags = tags == null ? List.of() : List.copyOf(tags);
        quellen = quellen == null ? List.of() : List.copyOf(quellen);
    }

    public enum Status {
        AKTIV,
        VERALTET
    }

    public enum Vertraulichkeit {
        NORMAL,
        INTERN
    }
}
