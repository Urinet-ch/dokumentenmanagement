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
    /**
     * Aufbewahrungsregel einer Dokumentart geändert (Req 9.10, entschieden von Fabian am 10.11.2026), mit alter und
     * neuer Regel, ohne Dokument
     */
    REGEL_AENDERN,
    /** Konfiguration geändert; Details enthalten Version, Quelle und die geänderten Elemente mit alten und neuen Werten. */
    KONFIGURATION_AENDERN,
    WISSEN_ANZEIGEN, WISSEN_ANNEHMEN, WISSEN_VERWERFEN, WISSEN_INGEST, WISSEN_LINT, WISSEN_IMPORT,
    WISSENSQUELLE_MARKIERT, WISSENSQUELLE_ENTFERNT,
    /**
     * Kontakt zusammengeführt (Req 17.7, 17.8): ein Eintrag ohne Dokument mit Quelle, Ziel, Grund und Zahlen, dazu je
     * umgehängtes Dokument einer mit Dokument-ID
     */
    KONTAKT_ZUSAMMENFUEHREN,
    /** Kontakt als inaktiv markiert bzw. wieder aktiviert (Req 17.11, 17.15), ohne Dokument */
    KONTAKT_INAKTIV, KONTAKT_AKTIV,
    BENUTZER_ANLEGEN, BENUTZER_AENDERN, BENUTZER_LOESCHEN, ROLLE_ZUWEISEN, BENUTZER_ANTRAG, BENUTZER_ABWEICHUNG,
    CHAT_BEWERTUNG_EXPORT,
    /** Betreiber-Kommando {@code archiv mandant wissen <code> ein|aus} (Spec 04, Req 22.2), ohne Dokument und Inhalte */
    WISSEN_FREIGESCHALTET, WISSEN_ABGESCHALTET
}
