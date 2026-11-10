package ch.urinet.archiv.mandant.application;

/**
 * Kein gültiger, aktiver Mandant für die Anfrage (Req 1.3): Claim fehlt, ist kein einzelner Code, unbekannt oder der
 * Mandant inaktiv. Die REST-Schicht antwortet 403, bevor Daten gelesen werden; die Meldung nennt keine Werte.
 */
public class MandantVerweigert extends RuntimeException {

    public MandantVerweigert(String meldung) {
        super(meldung);
    }
}
