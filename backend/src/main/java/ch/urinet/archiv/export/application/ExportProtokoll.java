package ch.urinet.archiv.export.application;

import java.util.UUID;

/**
 * Driven Port: Zugriffsprotokoll des Jahresexports (Spec 02, Req 7.3; design.md «Jahresexport»), Aktion
 * {@code EXPORTIEREN} bzw. {@code HERUNTERLADEN} mit der Export-ID an Stelle der Dokument-ID. Nur in einer laufenden
 * Transaktion (Req 10.2: synchron mit der Aktion).
 */
public interface ExportProtokoll {

    /** Anforderung über die API: Benutzer, Rolle und IP aus der Anfrage. */
    void angefordert(UUID exportId);

    /** Abschluss im Worker: Benutzer und Rolle der Anforderung, SHA-256 des ZIP (Req 7.3). */
    void abgeschlossen(UUID exportId, String benutzer, String rolle, String sha256);

    /** Download-Adresse ausgegeben: Benutzer, Rolle und IP aus der Anfrage, SHA-256 des ZIP. */
    void heruntergeladen(UUID exportId, String sha256);
}
