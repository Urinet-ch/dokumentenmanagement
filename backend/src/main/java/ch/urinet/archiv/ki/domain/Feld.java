package ch.urinet.archiv.ki.domain;

/**
 * Felder des KI-Vorschlags mit eigener Sicherheit (Req 7.2, 7.6); {@link #schluessel()} ist der Name in
 * {@code ki_vorschlag} und {@code ki_sicherheit} (wie im Antwortschema, liest die PWA).
 */
public enum Feld {
    ART("art"),
    TITEL("titel"),
    DATUM("datum"),
    PARTNER("partnerName"),
    BELEGNUMMER("belegnummer"),
    RICHTUNG("richtung"),
    BETRAG("betrag"),
    WAEHRUNG("waehrung"),
    FAELLIGKEIT("faelligkeit"),
    VERTRAULICHKEIT("vertraulichkeit"),
    /** Nicht im Antwortschema; Sicherheit 1 nur, wenn die Referenz aus dem Swiss QR Code stammt (Req 7.11). */
    QR_REFERENZ("qrReferenz");

    private final String schluessel;

    Feld(String schluessel) {
        this.schluessel = schluessel;
    }

    public String schluessel() {
        return schluessel;
    }
}
