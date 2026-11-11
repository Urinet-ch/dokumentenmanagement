package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Auswahl eines Driven Ports (Req 16.13; Review PR #25, Punkt 11): ob der Dev-Stub aktiv ist, ob der echte Adapter
 * gebaut ist und, falls nicht, mit welcher Aufgabe er folgt. Jede {@code *Auswahl} liefert einen Wert; die Startprüfung
 * wendet {@link #pruefe} in {@code prod} auf alle an.
 *
 * @param folgtMit Aufgabe, mit der der Adapter kommt; nur zulässig, solange er fehlt
 */
public record PortAuswahl(String port, boolean stub, boolean adapterVorhanden, Optional<String> folgtMit) {

    public PortAuswahl {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(folgtMit, "folgtMit");
        if (adapterVorhanden && folgtMit.isPresent()) {
            throw new IllegalArgumentException(port + ": Adapter vorhanden, «folgt mit» entfällt");
        }
    }

    /** Ergebnis der Prüfung in {@code prod}: Abbrüche und Warnungen, je mit dem Port. */
    public record Befund(List<String> abbrueche, List<String> warnungen) {

        public Befund {
            abbrueche = List.copyOf(abbrueche);
            warnungen = List.copyOf(warnungen);
        }
    }

    /**
     * In {@code prod} ist nie ein Stub aktiv, und ein fehlender Adapter bricht ab; nur ein Adapter, der laut Plan
     * später folgt ({@link #folgtMit}), ergibt eine Warnung, bis seine Aufgabe ihn baut.
     */
    public static Befund pruefe(List<PortAuswahl> auswahl) {
        List<String> abbrueche = new java.util.ArrayList<>();
        List<String> warnungen = new java.util.ArrayList<>();
        for (PortAuswahl port : auswahl) {
            if (port.stub()) {
                abbrueche.add(port.port() + ": Dev-Stub ist in prod nicht zulässig");
            } else if (!port.adapterVorhanden() && port.folgtMit().isEmpty()) {
                abbrueche.add(port.port() + ": kein Adapter");
            } else if (!port.adapterVorhanden()) {
                warnungen.add(port.port() + ": kein Adapter, folgt mit " + port.folgtMit().get());
            }
        }
        return new Befund(abbrueche, warnungen);
    }
}
