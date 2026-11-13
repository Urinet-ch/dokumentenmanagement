package ch.urinet.archiv.ki.application;

import java.util.UUID;

/**
 * Das Sprachmodell hat nicht geantwortet (Zeitüberschreitung, Verbindung, HTTP-Fehler): Fehlversuch des Auftrags
 * {@code KI_VORSCHLAG}, wiederholt nach Req 5.4; nach dem letzten Versuch geht das Dokument ohne Vorschlag in den
 * Eingangskorb, nie in {@code FEHLER} (Req 7.3). Die Meldung nennt nur die Dokument-ID und Klassennamen (Req 16.7).
 */
public class KiAusfall extends RuntimeException {

    public KiAusfall(UUID dokumentId, String grund) {
        super("Sprachmodell ohne Antwort für Dokument " + dokumentId + " (" + grund + ")");
    }
}
