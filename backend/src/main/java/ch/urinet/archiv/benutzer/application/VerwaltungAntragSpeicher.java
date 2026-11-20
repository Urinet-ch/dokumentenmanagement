package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface VerwaltungAntragSpeicher {

    enum Art { INHABER_ROLLE, LOESCHEN }

    enum Status { OFFEN, BESTAETIGT, ABGELEHNT, ABGELAUFEN, ZURUECKGEZOGEN }

    record Antrag(UUID id, Art art, String ziel, Rolle rolle, String grund, String beantragtVon,
                  Instant beantragtAm, Instant gueltigBis, Status status, String entschiedenVon,
                  Instant entschiedenAm) {
        public Antrag {
            Objects.requireNonNull(id);
            Objects.requireNonNull(art);
            Objects.requireNonNull(beantragtAm);
            Objects.requireNonNull(gueltigBis);
            Objects.requireNonNull(status);
            if (ziel == null || ziel.isBlank() || beantragtVon == null || beantragtVon.isBlank()
                    || !gueltigBis.isAfter(beantragtAm)
                    || (art == Art.INHABER_ROLLE && rolle != Rolle.INHABER)
                    || (art == Art.LOESCHEN && (rolle != null || grund == null || grund.isBlank()))
                    || (grund != null && (grund.length() > 500 || grund.chars().anyMatch(Character::isISOControl)))
                    || (status == Status.OFFEN && (entschiedenVon != null || entschiedenAm != null))
                    || (status != Status.OFFEN && (entschiedenAm == null || entschiedenAm.isBefore(beantragtAm)))
                    || (status != Status.OFFEN && status != Status.ABGELAUFEN
                        && (entschiedenVon == null || entschiedenVon.isBlank()))) {
                throw new IllegalArgumentException("Antrag ist ungültig");
            }
        }

        @Override
        public String toString() {
            return "Antrag[geschützt]";
        }
    }

    void anlegen(Antrag antrag);

    Optional<Antrag> lesen(UUID id);

    boolean entscheiden(UUID id, Status status, String akteur, Instant zeit);
}
