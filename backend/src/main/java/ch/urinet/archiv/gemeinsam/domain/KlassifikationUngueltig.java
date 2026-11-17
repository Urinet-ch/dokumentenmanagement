package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;

public final class KlassifikationUngueltig extends FachFehler {

    private final List<String> klassifikationen;

    public KlassifikationUngueltig(List<String> klassifikationen) {
        super(Fehlercode.KLASSIFIKATION_UNGUELTIG);
        this.klassifikationen = List.copyOf(klassifikationen);
    }

    public List<String> klassifikationen() {
        return klassifikationen;
    }
}
