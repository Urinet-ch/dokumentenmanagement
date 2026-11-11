package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.CsvTabelle;

/**
 * Liest eine CSV-Datei (Req 13.1): erkennt Zeichensatz (UTF-8 mit oder ohne BOM, Windows-1252) und Trennzeichen
 * ({@code ;} oder {@code ,}) und parst nach RFC 4180.
 */
public interface CsvLeser {

    /**
     * @throws ch.urinet.archiv.gemeinsam.domain.FachFehler {@code CSV_LEER} ohne Kopfzeile, {@code CSV_UNGUELTIG} bei
     *         unbekanntem Zeichensatz, kaputtem Format oder mehr als {@link CsvTabelle#MAX_ZEILEN} Datenzeilen
     */
    CsvTabelle lesen(byte[] inhalt);
}
