package ch.urinet.archiv.protokoll.domain;

/**
 * Aktionen des Zugriffsprotokolls (design.md «Protokoll und Manifest»); gespeichert als Text, die Liste stimmt mit dem
 * Check {@code zugriff_protokoll_aktion_ck} überein.
 */
public enum ProtokollAktion {
    ERFASSEN, ANZEIGEN, HERUNTERLADEN, AENDERN, BESTAETIGEN, VERWERFEN, WIEDERHOLEN, EXPORTIEREN,
    /** jede 403 oder 404 nach Req 1.4, 2.3 oder 2.6, mit Fehlercode (Req 10.7) */
    VERWEIGERT,
    /** Systemaktion bei Req 9.7 */
    SPERRE_KONFLIKT,
    WISSEN_ANZEIGEN, WISSEN_ANNEHMEN, WISSEN_VERWERFEN, WISSEN_INGEST, WISSEN_LINT
}
