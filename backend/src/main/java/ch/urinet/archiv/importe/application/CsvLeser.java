package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.Kopfzeile;

import java.nio.file.Path;

/**
 * Liest eine CSV-Datei (Req 13.1): erkennt Zeichensatz (UTF-8 mit oder ohne BOM, Windows-1252) und Trennzeichen
 * ({@code ;} oder {@code ,}) am Anfang der Datei und parst nach RFC 4180 Zeile für Zeile, ohne die Datei als Ganzes im
 * Speicher zu halten (Entscheid Fabian 11.11.2026).
 */
public interface CsvLeser {

    /**
     * Prüft die ganze Datei vor (Zeichensatz, Spalten- und Zeilenzahl, Länge der Kopfzeile), meldet dann die Kopfzeile
     * und danach jede Datenzeile einzeln an den {@code empfaenger}. Ein Feld über {@code feldGrenze} Zeichen meldet der
     * Leser auf {@code feldGrenze + 1} Zeichen gekürzt: Es bleibt über der Grenze (die Zeile ist «fehlerhaft»), belegt
     * aber nie mehr Speicher. Fehler des Empfängers gehen unverändert weiter.
     *
     * @throws ch.urinet.archiv.gemeinsam.domain.FachFehler {@code CSV_LEER} ohne Kopfzeile, {@code CSV_UNGUELTIG} bei
     *         unbekanntem Zeichensatz, kaputtem Format, mehr als {@link CsvTabelle#MAX_SPALTEN} Spalten, mehr als
     *         {@link CsvTabelle#MAX_ZEILEN} Datenzeilen oder einer Kopfzeile über {@link CsvTabelle#MAX_KOPF} Zeichen
     *         bzw. mit einem Namen über {@link CsvTabelle#MAX_KOPF_NAME} Zeichen
     */
    void lesen(Path datei, int feldGrenze, Empfaenger empfaenger);

    /** Nimmt Kopfzeile und Datenzeilen in der Reihenfolge der Datei entgegen. */
    interface Empfaenger {

        void kopf(String zeichensatz, char trennzeichen, Kopfzeile kopf);

        void zeile(CsvTabelle.Zeile zeile);
    }
}
