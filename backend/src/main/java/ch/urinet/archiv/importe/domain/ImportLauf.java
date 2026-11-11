package ch.urinet.archiv.importe.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein Lauf des CSV-Imports ({@code import_lauf}): Datei (Name, SHA-256, Zeichensatz, Trennzeichen, Kopfzeile), Zähler
 * und Status. Die Zähler des Abgleichs ({@code neu}, {@code geaendert} …) setzt erst die Vorschau (Aufgabe 4.2).
 */
public record ImportLauf(UUID id, ImportArt art, boolean vollexport, String dateiname, String sha256,
                         String zeichensatz, char trennzeichen, Kopfzeile kopf, int zeilen, int fehler,
                         int uebersprungen, LaufStatus status, String benutzer, Instant erstellt) {

    public ImportLauf {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(art, "art");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(kopf, "kopf");
        Objects.requireNonNull(status, "status");
    }

    public ImportLauf mit(LaufStatus neuerStatus, int neueFehler, int neueUebersprungen) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen,
                neueFehler, neueUebersprungen, neuerStatus, benutzer, erstellt);
    }
}
