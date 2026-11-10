package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

/** Zugriff verweigert; die REST-Schicht antwortet mit dem {@link Fehlercode} des {@link Grund}s (Problem Details). */
public class Verweigerung extends FachFehler {

    public enum Grund {
        /** HTTP 404: fremder Mandant oder für den Benutzer unsichtbar (Req 1.4, 2.3) */
        NICHT_GEFUNDEN(Fehlercode.NICHT_GEFUNDEN),
        /** HTTP 403: Recht der Aktion fehlt oder keine Archiv-Rolle (Req 2.2, 2.6) */
        NICHT_ERLAUBT(Fehlercode.NICHT_ERLAUBT),
        /** HTTP 403: kein gültiger, aktiver Mandant (Req 1.3); ohne Mandanten-Schema nicht protokolliert */
        MANDANT_FEHLT(Fehlercode.MANDANT_FEHLT);

        private final Fehlercode fehlercode;

        Grund(Fehlercode fehlercode) {
            this.fehlercode = fehlercode;
        }

        public Fehlercode fehlercode() {
            return fehlercode;
        }
    }

    private final Grund grund;

    public Verweigerung(Grund grund) {
        super(grund.fehlercode());
        this.grund = grund;
    }

    public Grund grund() {
        return grund;
    }
}
