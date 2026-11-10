package ch.urinet.archiv.sicherheit.application;

/** Zugriff verweigert; der Adapter übersetzt {@link Grund} in den HTTP-Status (404 bzw. 403). */
public class Verweigerung extends RuntimeException {

    public enum Grund {
        /** HTTP 404: fremder Mandant oder für den Benutzer unsichtbar (Req 1.4, 2.3) */
        NICHT_GEFUNDEN,
        /** HTTP 403: Recht der Aktion fehlt, kein gültiger Mandant oder keine eindeutige Archiv-Rolle (Req 1.3, 2.6) */
        NICHT_ERLAUBT
    }

    private final Grund grund;

    public Verweigerung(Grund grund) {
        super(grund.name());
        this.grund = grund;
    }

    public Grund grund() {
        return grund;
    }
}
