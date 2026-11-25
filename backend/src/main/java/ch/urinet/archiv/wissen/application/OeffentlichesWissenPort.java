package ch.urinet.archiv.wissen.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OeffentlichesWissenPort {
    record Stand(UUID id, int version, long quellenRevision) {
    }

    record Indexzeile(Stand stand, String pfad, String titel, String typ, String beschreibung) {
    }

    record Dokument(UUID id, String titel, String reiheCode, String reiheName, String ausgabe) {
    }

    record Seite(Stand version, String pfad, String titel, String typ, String inhalt, Instant freigegebenAm,
                 List<Dokument> dokumente) {
        public Seite {
            dokumente = List.copyOf(dokumente);
        }
    }

    boolean hatFreigegebeneSeite();

    List<Indexzeile> suchen(String begriff, int hoechstens);

    Optional<Seite> seite(String pfad);

    boolean aktuell(Stand stand);
}
