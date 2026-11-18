package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;

public final class RegelVerletzt extends FachFehler {

    private final List<Befund> regeln;

    public RegelVerletzt(List<Befund> regeln) {
        super(Fehlercode.REGEL_VERLETZT);
        this.regeln = List.copyOf(regeln);
    }

    public List<Befund> regeln() {
        return regeln;
    }

    public record Befund(String code, String meldung) {
    }
}
