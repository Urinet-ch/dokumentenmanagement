package ch.urinet.archiv.dokument.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Felder des KI-Vorschlags, die der Eingangskorb als «bitte prüfen» markiert (Req 7.6, design.md «KI»). */
public final class KiSicherheit {

    private KiSicherheit() {
    }

    /**
     * Jedes vorgeschlagene oder bewertete Feld, dessen Sicherheit unter der Schwelle liegt; fehlt die Sicherheit eines
     * vorgeschlagenen Felds, gilt es als unsicher. Alphabetisch sortiert.
     */
    public static List<String> unsicher(Set<String> vorgeschlagen, Map<String, BigDecimal> sicherheit,
                                        BigDecimal schwelle) {
        Objects.requireNonNull(schwelle, "schwelle");
        TreeSet<String> felder = new TreeSet<>(vorgeschlagen);
        felder.addAll(sicherheit.keySet());
        return felder.stream().filter(feld -> {
            BigDecimal wert = sicherheit.get(feld);
            return wert == null || wert.compareTo(schwelle) < 0;
        }).toList();
    }
}
