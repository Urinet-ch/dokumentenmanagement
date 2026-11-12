package ch.urinet.archiv.protokoll.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Was das Archiv bei einer Aktion über die Kennung weiss (Req 10.10): Name aus der Anmeldung (fehlt z. B. bei einer
 * Systemaktion) und Rolle des Protokolleintrags. Entscheidet, ob das Benutzerverzeichnis eine neue Zeile braucht.
 */
public record Benutzerangaben(String benutzer, Optional<String> name, Optional<String> rolle) {

    public Benutzerangaben {
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rolle, "rolle");
        if (benutzer.isBlank()) {
            throw new IllegalArgumentException("benutzer leer");
        }
        name = name.map(String::strip).filter(n -> !n.isEmpty());
    }

    /** Die neue Zeile zu einer Aktion; gültig ab der Zeit der Datenbank. */
    public record NeueZeile(Optional<String> name, Optional<String> rolle) {
    }

    /**
     * Neue Zeile, wenn es zur Kennung noch keine gibt, die letzte beendet ist (wieder aktiv nach Austritt oder
     * Widerruf) oder sich Rolle oder bekannter Name geändert haben. Ein unbekannter Name übernimmt den bisherigen,
     * damit Systemaktionen die Zuordnung nicht verlieren.
     */
    public Optional<NeueZeile> neueZeile(Optional<VerzeichnisEintrag> letzter) {
        Objects.requireNonNull(letzter, "letzter");
        if (letzter.isEmpty()) {
            return Optional.of(new NeueZeile(name, rolle));
        }
        VerzeichnisEintrag bisher = letzter.get();
        Optional<String> neuerName = name.isPresent() ? name : bisher.name();
        if (bisher.beendet() || !neuerName.equals(bisher.name()) || !rolle.equals(bisher.rolle())) {
            return Optional.of(new NeueZeile(neuerName, rolle));
        }
        return Optional.empty();
    }
}
