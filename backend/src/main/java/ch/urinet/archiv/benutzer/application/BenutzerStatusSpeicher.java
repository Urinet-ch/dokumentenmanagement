package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsgrund;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public interface BenutzerStatusSpeicher {

    /** {@code grund} ist die feste Kategorie nach Requirement 4.4, kein Freitext. */
    record Eintrag(BenutzerStatus status, Instant seit, Verwaltungsgrund grund, String von) {
        public Eintrag {
            if (status != BenutzerStatus.GESPERRT && status != BenutzerStatus.DEAKTIVIERT) {
                throw new IllegalArgumentException("Nur gesperrte und deaktivierte Benutzer werden gespeichert");
            }
            Objects.requireNonNull(seit);
            if (grund == null || von == null || von.isBlank() || von.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Grund oder Akteur ungültig");
            }
        }

        @Override
        public String toString() {
            return "Eintrag[geschützt]";
        }
    }

    Optional<Eintrag> lesen(String benutzer);

    void setzen(String benutzer, Eintrag eintrag);

    boolean entfernen(String benutzer);
}
