package ch.urinet.archiv.mandant.adapter.in.start;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Liest {@code archiv.rolle} (z. B. {@code api, worker}) überall gleich: an Kommas getrennt, getrimmt, ohne Leere. */
final class Rollen {

    private Rollen() {
    }

    static Set<String> lesen(String wert) {
        if (wert == null) {
            return Set.of();
        }
        return Arrays.stream(wert.split(",")).map(String::trim).filter(rolle -> !rolle.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
