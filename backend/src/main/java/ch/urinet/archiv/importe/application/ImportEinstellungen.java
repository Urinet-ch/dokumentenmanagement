package ch.urinet.archiv.importe.application;

/**
 * Grenzen des CSV-Imports: {@code maxGroesse} in Bytes ({@code archiv.import.max-groesse}, Standard 5 MiB, Entscheid
 * Fabian 11.11.2026; grössere Dateien → 413 {@code DATEI_ZU_GROSS}, bevor der Parser läuft) und {@code feldGrenze} in
 * Zeichen pro Feld ({@code archiv.import.feld-grenze}).
 */
public record ImportEinstellungen(long maxGroesse, int feldGrenze) {

    public ImportEinstellungen {
        if (maxGroesse < 1 || feldGrenze < 1) {
            throw new IllegalArgumentException("Grenzen des CSV-Imports müssen positiv sein");
        }
    }
}
