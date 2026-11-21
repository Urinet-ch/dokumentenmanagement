package ch.urinet.archiv.benutzer.application;

import java.time.Instant;

/** Zeiten aus dem Zugriffsprotokoll des aktuellen Mandanten. */
public interface BenutzerAktivitaet {

    record LetzteAktion(Instant zeit, String aktion) {
    }

    record Angaben(Instant einladungSeit, LetzteAktion letzteAktion) {
    }

    Angaben lesen(String benutzer);
}
