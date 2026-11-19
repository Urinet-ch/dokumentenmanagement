package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;

/** 422 {@code KONFIGURATION_UNGUELTIG} mit Befunden (Pfad, Code, Meldung), ohne Werte aus der Eingabe. */
public final class KonfigurationUngueltig extends FachFehler {

    private final List<Befund> befunde;

    public KonfigurationUngueltig(List<Befund> befunde) {
        super(Fehlercode.KONFIGURATION_UNGUELTIG);
        this.befunde = List.copyOf(befunde);
    }

    public List<Befund> befunde() {
        return befunde;
    }

    public record Befund(String pfad, String code, String meldung) {
    }
}
