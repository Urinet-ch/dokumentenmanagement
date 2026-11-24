package ch.urinet.archiv.ki.domain.oeffentlich;

import java.util.List;

public record OeffentlicheWissensquelle(String titel, String pfad, List<Beleg> belege) {
    public OeffentlicheWissensquelle {
        belege = List.copyOf(belege);
    }

    public record Beleg(String titel, String reiheCode, String reiheName, String ausgabe) { }
}
