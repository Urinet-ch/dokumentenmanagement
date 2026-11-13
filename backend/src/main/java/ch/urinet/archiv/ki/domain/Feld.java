package ch.urinet.archiv.ki.domain;

/**
 * Felder des KI-Vorschlags (Req 7.2); {@link #schluessel()} ist der Name in {@code ki_vorschlag} und
 * {@code ki_sicherheit} (wie im Antwortschema, liest die PWA). Eine eigene Sicherheit (Req 7.6) tragen alle ausser
 * {@link #TAGS} ({@link #mitSicherheit()}); Tags zählen nur für die Korrektur (Req 7.7).
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
    QR_REFERENZ("qrReferenz"),
    /** Ohne eigene Sicherheit; für die Korrektur als Menge verglichen ({@link Korrektur#tags}). */
    TAGS("tags");

    private final String schluessel;

    Feld(String schluessel) {
        this.schluessel = schluessel;
    }

    public String schluessel() {
        return schluessel;
    }

    public boolean mitSicherheit() {
        return this != TAGS;
    }
}
