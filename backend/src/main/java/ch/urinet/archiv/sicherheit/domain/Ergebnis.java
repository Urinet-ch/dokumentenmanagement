package ch.urinet.archiv.sicherheit.domain;

/** Zelle der Rechtematrix (Req 2.6). Die bedingten Zellen schränkt die Sichtbarkeit je Dokument ein (A2–A5). */
public enum Ergebnis {
    /** «erlaubt» */
    ERLAUBT,
    /** «403»: HTTP 403, nichts geändert */
    VERBOTEN,
    /** «404»: das Dokument gilt als nicht vorhanden (Req 2.3) */
    NICHT_SICHTBAR,
    /** «nur eigene ²»: bestätigt mit {@code person_benutzer} = Benutzer, oder unbestätigt und von ihm erfasst */
    NUR_EIGENE,
    /** «erlaubt (nur lesend)» */
    NUR_LESEND,
    /** «nur selbst erfasste» */
    NUR_SELBST_ERFASSTE;

    /** Die Aktion ist für die Rolle grundsätzlich zugänglich; Einschränkungen je Dokument prüft die Sichtbarkeit. */
    public boolean zugaenglich() {
        return this != VERBOTEN;
    }
}
