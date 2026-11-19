package ch.urinet.archiv.wissen.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Wissensseite(UUID id, String pfad, Seitentyp typ, String titel, String beschreibung, List<String> tags,
                           Status status, LocalDate veraltetAb, List<Quelleneintrag> quellen,
                           Vertraulichkeit vertraulichkeit, String inhalt, int version, String erzeugtVon,
                           Instant erzeugtAm, String freigegebenVon, Instant freigegebenAm, boolean quellePruefen,
                           boolean widerspruchOffen, UUID wissensquelleId, long quellenRevision) {

    public Wissensseite {
        tags = tags == null ? List.of() : List.copyOf(tags);
        quellen = quellen == null ? List.of() : List.copyOf(quellen);
    }

    public Wissensseite(UUID id, String pfad, Seitentyp typ, String titel, String beschreibung, List<String> tags,
                        Status status, LocalDate veraltetAb, List<Quelleneintrag> quellen,
                        Vertraulichkeit vertraulichkeit, String inhalt, int version, String erzeugtVon,
                        Instant erzeugtAm, String freigegebenVon, Instant freigegebenAm, boolean quellePruefen,
                        boolean widerspruchOffen, UUID wissensquelleId) {
        this(id, pfad, typ, titel, beschreibung, tags, status, veraltetAb, quellen, vertraulichkeit, inhalt, version,
                erzeugtVon, erzeugtAm, freigegebenVon, freigegebenAm, quellePruefen, widerspruchOffen,
                wissensquelleId, 0);
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
