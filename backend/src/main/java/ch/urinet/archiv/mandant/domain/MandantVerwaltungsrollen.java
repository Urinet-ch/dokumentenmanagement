package ch.urinet.archiv.mandant.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Verwaltungsrollen, die für einen Mandanten freigeschaltet sind. */
public record MandantVerwaltungsrollen(List<String> werte) {

    private static final Set<String> ZULAESSIGE_ROLLEN =
            Set.of("inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle");

    public static final MandantVerwaltungsrollen STANDARD =
            new MandantVerwaltungsrollen(List.of("inhaber", "mitarbeiter", "treuhaender"));

    public MandantVerwaltungsrollen {
        if (werte == null || werte.isEmpty()
                || werte.stream().anyMatch(rolle -> rolle == null || !ZULAESSIGE_ROLLEN.contains(rolle))
                || new HashSet<>(werte).size() != werte.size()) {
            throw new IllegalArgumentException("Ungültige Verwaltungsrollen");
        }
        werte = List.copyOf(werte);
    }

    public String[] alsArray() {
        return werte.toArray(String[]::new);
    }
}
