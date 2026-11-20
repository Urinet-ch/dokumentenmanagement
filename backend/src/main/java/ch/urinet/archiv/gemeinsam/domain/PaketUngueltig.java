package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;
import java.util.Map;

public final class PaketUngueltig extends FachFehler {
    private final List<Map<String, String>> befunde;

    public PaketUngueltig(String code, String pfad) {
        super(Fehlercode.PAKET_UNGUELTIG);
        befunde = List.of(Map.of("code", code, "pfad", pfad));
    }

    public List<Map<String, String>> befunde() {
        return befunde;
    }
}
