package ch.urinet.archiv.ki.domain;

import java.util.List;

/** Für die Erfassung freigegebene Klassifikation mit ihren auswählbaren Werten. */
public record KiKlassifikation(String code, String name, String beschreibung, boolean mehrfach, List<Wert> werte) {

    public KiKlassifikation {
        werte = werte == null ? List.of() : List.copyOf(werte);
    }

    public record Wert(String code, String name, String beschreibung, boolean aktiv) {
    }
}
