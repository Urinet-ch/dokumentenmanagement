package ch.urinet.archiv.ki.domain.oeffentlich;

import java.util.List;
import java.util.UUID;

public record OeffentlicheWissensquelle(String titel, String pfad, List<Beleg> belege) {
    public OeffentlicheWissensquelle {
        belege = List.copyOf(belege);
    }

    public record Beleg(UUID dokumentId, String titel, String reiheCode, String reiheName, String ausgabe) { }
}
