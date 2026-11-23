package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record BestaetigteEinstellungen(Set<String> schluessel, boolean gueltig) {
    private static final Set<String> KATALOG = Einstellungskatalog.alle().stream()
            .map(Einstellungskatalog.Einstellung::schluessel).collect(Collectors.toUnmodifiableSet());

    public BestaetigteEinstellungen {
        schluessel = Set.copyOf(schluessel);
    }

    public static BestaetigteEinstellungen lesen(String text) {
        if (text == null || text.isBlank()) {
            return new BestaetigteEinstellungen(Set.of(), true);
        }
        if (text.length() > 1024) {
            return new BestaetigteEinstellungen(Set.of(), false);
        }
        var teile = Arrays.stream(text.split(",", -1)).map(String::strip).toList();
        Set<String> bekannt = teile.stream().filter(KATALOG::contains).collect(Collectors.toUnmodifiableSet());
        return new BestaetigteEinstellungen(bekannt, teile.size() <= KATALOG.size()
                && teile.stream().allMatch(KATALOG::contains));
    }

    public void pruefen() {
        if (!gueltig) {
            throw new KonfigurationUngueltig(java.util.List.of(new KonfigurationUngueltig.Befund("bestaetigt",
                    "BESTAETIGUNG_UNGUELTIG", "Die Bestätigung enthält unbekannte oder zu viele Schlüssel.")));
        }
    }
}
