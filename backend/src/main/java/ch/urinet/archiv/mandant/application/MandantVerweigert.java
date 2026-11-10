package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

/**
 * Kein gültiger, aktiver Mandant für die Anfrage (Req 1.3): Claim fehlt, ist kein einzelner Code, unbekannt oder der
 * Mandant inaktiv. Die REST-Schicht antwortet 403 {@code MANDANT_FEHLT}, bevor Daten gelesen werden; die Meldung nennt
 * keine Werte.
 */
public class MandantVerweigert extends FachFehler {

    public MandantVerweigert(String meldung) {
        super(Fehlercode.MANDANT_FEHLT, meldung);
    }
}
