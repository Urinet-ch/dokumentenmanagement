package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

public interface InhaberEinladungPort {
    void einladen(MandantCode mandant, Eingabe eingabe);

    record Eingabe(String benutzername, String email, String vorname, String nachname, String betreiber) {
        @Override
        public String toString() {
            return "Eingabe[geschützt]";
        }
    }
}
