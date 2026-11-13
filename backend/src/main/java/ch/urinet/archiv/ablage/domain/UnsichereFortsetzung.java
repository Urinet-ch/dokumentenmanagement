package ch.urinet.archiv.ablage.domain;

import java.util.Optional;

/**
 * Die Versionsliste lässt sich nicht sicher fortsetzen: Der Object Storage hat eine abgeschnittene Antwort ohne
 * {@code NextKeyMarker} oder {@code NextVersionIdMarker} geliefert, oder eine gespeicherte Marke hat keine Versions-ID.
 * Statt Versionen still zu überspringen, bricht der Teil des Prüflaufs ab; die Auftragsschlange wiederholt ihn, nach
 * dem letzten Versuch endet er in {@code FEHLER} und der Inhaber erhält eine Meldung (Req 15.5, 15.10). Die Meldung
 * nennt nur Präfix und Schlüssel (Req 16.7).
 */
public class UnsichereFortsetzung extends IllegalStateException {

    public UnsichereFortsetzung(String praefix, Optional<String> schluessel) {
        super("Versionsliste unter " + praefix + " nicht sicher fortsetzbar"
                + schluessel.map(s -> " nach " + s).orElse("") + ": Marker des Object Storage fehlt");
    }
}
