package ch.urinet.archiv.konfiguration.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** Erkannte, dateinamebasierte Felder ohne den Dateinamen selbst. */
public record DateinamenTreffer(int regelNr, String art, Map<String, String> felder) {

    public DateinamenTreffer {
        felder = Map.copyOf(felder);
    }

    public Map<String, Object> alsMap() {
        Map<String, Object> ergebnis = new LinkedHashMap<>();
        ergebnis.put("nr", regelNr);
        ergebnis.put("art", art);
        ergebnis.put("felder", felder);
        ergebnis.put("herkunft", "DATEINAME");
        return Map.copyOf(ergebnis);
    }
}
