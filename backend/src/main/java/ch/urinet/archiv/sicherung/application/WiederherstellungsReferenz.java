package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

/** Aktualisiert nach dem Restore die Versions-ID einer Dokumentdatei. */
@FunctionalInterface
public interface WiederherstellungsReferenz {

    void versionErsetzt(MandantCode mandant, String schluessel, String alteVersion, String neueVersion);
}
