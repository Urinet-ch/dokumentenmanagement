package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;

public final class BestaetigungNoetig extends FachFehler {
    private final List<Auswirkung> auswirkung;

    public BestaetigungNoetig(List<Auswirkung> auswirkung) {
        super(Fehlercode.BESTAETIGUNG_NOETIG);
        this.auswirkung = List.copyOf(auswirkung);
    }

    public List<Auswirkung> auswirkung() {
        return auswirkung;
    }

    public record Auswirkung(String schluessel, String text, long betroffeneDatensaetze) {
    }
}
