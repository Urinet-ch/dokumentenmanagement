package ch.urinet.archiv.importe.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein Lauf des CSV-Imports ({@code import_lauf}): Datei (Name, SHA-256, Zeichensatz, Trennzeichen, Kopfzeile), Zähler
 * und Status. Die Zähler des Abgleichs ({@code vorschau}: neu, geändert …) setzt erst die Vorschau (Aufgabe 4.2);
 * {@code zuordnung} ist die Zuordnung, mit der die Zeilen geprüft wurden (fehlt, solange keine gilt).
 */
public record ImportLauf(UUID id, ImportArt art, boolean vollexport, String dateiname, String sha256,
                         String zeichensatz, char trennzeichen, Kopfzeile kopf, int zeilen, int fehler,
                         int uebersprungen, LaufStatus status, String benutzer, Instant erstellt, Zuordnung zuordnung,
                         Zaehler vorschau) {

    public ImportLauf {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(art, "art");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(kopf, "kopf");
        Objects.requireNonNull(status, "status");
    }

    public ImportLauf(UUID id, ImportArt art, boolean vollexport, String dateiname, String sha256, String zeichensatz,
                      char trennzeichen, Kopfzeile kopf, int zeilen, int fehler, int uebersprungen, LaufStatus status,
                      String benutzer, Instant erstellt) {
        this(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen, fehler, uebersprungen,
                status, benutzer, erstellt, null, null);
    }

    public ImportLauf mit(LaufStatus neuerStatus, int neueFehler, int neueUebersprungen) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen,
                neueFehler, neueUebersprungen, neuerStatus, benutzer, erstellt, zuordnung, vorschau);
    }

    public ImportLauf mit(Zuordnung neueZuordnung) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen, fehler,
                uebersprungen, status, benutzer, erstellt, neueZuordnung, vorschau);
    }

    /** Abgeschlossene Vorschau: Status {@code VORSCHAU} mit den Zählern. */
    public ImportLauf mitVorschau(Zaehler zaehler) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen,
                zaehler.fehler(), zaehler.uebersprungen(), LaufStatus.VORSCHAU, benutzer, erstellt, zuordnung,
                zaehler);
    }

    /** Zahlen der Vorschau (Req 13.3): neu, geändert, unverändert, mögliche Dublette, fehlerhaft, übersprungen. */
    public record Zaehler(int neu, int geaendert, int unveraendert, int dubletten, int fehler, int uebersprungen) {
    }
}
