package ch.urinet.archiv.export.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.UUID;

/**
 * Driven Port: meldet einen fertigen Jahresexport per E-Mail (Spec 02, Req 7.2, 7.6): nur der Link auf die Seite
 * «Export» und die Export-ID, keine Dokumentinhalte. Ohne Empfänger oder bei einem Fehler beim Versand bleibt ein
 * Log-Eintrag mit der Export-ID; der Aufrufer scheitert nie daran.
 */
public interface ExportBenachrichtigung {

    void fertig(MandantCode mandant, UUID exportId);
}
