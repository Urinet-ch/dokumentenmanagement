package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public interface BenutzerStatusSpeicher {

    record Eintrag(BenutzerStatus status, Instant seit, String grund, String von) {
        public Eintrag {
            if (status != BenutzerStatus.GESPERRT && status != BenutzerStatus.DEAKTIVIERT) {
                throw new IllegalArgumentException("Nur gesperrte und deaktivierte Benutzer werden gespeichert");
            }
            Objects.requireNonNull(seit);
            if (grund == null || grund.isBlank() || grund.length() < 10 || grund.length() > 500
                    || grund.chars().anyMatch(Character::isISOControl) || von == null || von.isBlank()) {
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
