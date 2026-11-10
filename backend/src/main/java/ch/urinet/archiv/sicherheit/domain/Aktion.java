package ch.urinet.archiv.sicherheit.domain;

/**
 * Zeilen der Rechtematrix (Req 2.6). Die Nummer entspricht der Spalte «Nr.» in requirements.md; Spec 04 hängt A15–A19
 * hinten an (Requirement 12.1).
 */
public enum Aktion {
    A1("Dokumente erfassen (Upload, Handy)"),
    A2("NORMAL lesen, suchen, herunterladen"),
    A3("INTERN lesen, suchen, herunterladen"),
    A4("PERSONAL lesen, suchen, herunterladen"),
    A5("Eingangskorb einsehen"),
    A6("Metadaten ändern, Vorschläge korrigieren"),
    A7("Bestätigen"),
    A8("Vertraulichkeit und person_benutzer setzen"),
    A9("Verwerfen"),
    A10("Verarbeitung oder Archivierung wiederholen"),
    A11("Dokumentarten verwalten"),
    A12("Kontakte ändern, CSV-Import"),
    A13("Zugriffsprotokoll einsehen"),
    A14("API-Tokens verwalten");

    private final String bezeichnung;

    Aktion(String bezeichnung) {
        this.bezeichnung = bezeichnung;
    }

    public String bezeichnung() {
        return bezeichnung;
    }
}
