package ch.urinet.archiv.wissen.domain.paket;

import java.time.LocalDate;
import java.util.List;

public record PaketSeite(String pfad, String typ, String titel, String beschreibung, List<String> tags,
                         LocalDate staleAfter, Long basisVersion, String erzeugtVon, List<PaketQuelle> sources,
                         String inhalt) {
    public PaketSeite {
        tags = List.copyOf(tags);
        sources = List.copyOf(sources);
    }
}
