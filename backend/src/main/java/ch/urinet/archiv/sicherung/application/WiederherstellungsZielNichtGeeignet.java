package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

/** Das Wiederherstellungsziel eines Mandanten erfüllt die nötigen S3-Voraussetzungen nicht. */
public final class WiederherstellungsZielNichtGeeignet extends RuntimeException {

    public WiederherstellungsZielNichtGeeignet(MandantCode mandant) {
        super("Wiederherstellungsziel für Mandant " + mandant.wert()
                + " fehlt oder hat keine aktivierte Versionierung und Object Lock.");
    }
}
