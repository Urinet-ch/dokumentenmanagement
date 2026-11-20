package ch.urinet.archiv.sicherheit.domain;

/**
 * Zeilen der Rechtematrix (Req 2.6). Die Nummer entspricht der Spalte «Nr.» in requirements.md; Spec 04 ergänzt A15–A19
 * und Spec 06 A20–A23.
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
    A14("API-Tokens verwalten"),
    A15("Wissensseiten, Index, Versionen und Log lesen (Sichtbarkeit nach 11.2)"),
    A16("Ingest einer Wissensquelle starten oder wiederholen"),
    A17("Entwürfe einsehen, Seiten von Hand ändern, Änderungen annehmen oder verwerfen, Lint starten"),
    A18("Chat-Antwort als Analyse vorschlagen, Status eigener Vorschläge sehen"),
    A19("Wissensbasis verwalten (Einstellungen, OKF-Export)"),
    A20("Mandanten-Einstellungen ansehen und ändern (Katalog, Gruppen Mandant, Kennungen, Chat und Aufbewahrung)"),
    A21("Benutzer, Rollen und Rechte-Übersicht ansehen"),
    A22("Benutzer verwalten (einladen, sperren, entsperren, deaktivieren, reaktivieren, Zugang zurücksetzen, E-Mail ändern, "
            + "Google-Verknüpfung lösen, löschen)"),
    A23("Rollen zuweisen");

    private final String bezeichnung;

    Aktion(String bezeichnung) {
        this.bezeichnung = bezeichnung;
    }

    public String bezeichnung() {
        return bezeichnung;
    }

    /**
     * A2–A10 und A16 (Ingest einer Wissensquelle, Spec 04, Req 12.1) betreffen ein einzelnes Dokument: Endpunkte mit
     * {@code {id}} prüfen zuerst die Sichtbarkeit (404), dann das Recht (403); {@code @RolesAllowed} lässt darum alle
     * Archiv-Rollen zu (Req 2.6, ADR-009 Entscheid 7). Ein A16-Endpunkt, dessen {@code {id}} kein Dokument ist, trägt
     * {@code @NichtDokumentbezogen}.
     */
    public boolean dokumentbezogen() {
        return (compareTo(A2) >= 0 && compareTo(A10) <= 0) || this == A16;
    }
}
