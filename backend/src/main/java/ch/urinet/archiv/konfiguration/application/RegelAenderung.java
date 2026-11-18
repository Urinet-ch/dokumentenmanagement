package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Regel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record RegelAenderung(Set<String> gesetzteFelder, String art, String typ, Map<String, Object> parameter,
                             String schwere, String meldung, Boolean aktiv) {

    public RegelAenderung {
        gesetzteFelder = Set.copyOf(gesetzteFelder);
        parameter = parameter == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(parameter));
    }

    public boolean hat(String feld) {
        return gesetzteFelder.contains(feld);
    }
}
