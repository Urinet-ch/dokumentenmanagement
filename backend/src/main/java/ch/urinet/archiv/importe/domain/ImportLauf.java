package ch.urinet.archiv.importe.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein Lauf des CSV-Imports ({@code import_lauf}): Datei (Name, SHA-256, Zeichensatz, Trennzeichen, Kopfzeile), Zähler
 * und Status. Die Zähler des Abgleichs ({@code vorschau}: neu, geändert …) setzt erst die Vorschau (Aufgabe 4.2);
 * {@code zuordnung} ist die Zuordnung, mit der die Zeilen geprüft wurden (fehlt, solange keine gilt).
 * {@code uebernahme} hält fest, wer die Übernahme ausgelöst hat, wann sie fertig war und was sie bewirkt hat (Protokoll,
 * Req 13.8, Aufgabe 4.3); fehlt, solange niemand übernommen hat.
 */
public record ImportLauf(UUID id, ImportArt art, boolean vollexport, String dateiname, String sha256,
                         String zeichensatz, char trennzeichen, Kopfzeile kopf, int zeilen, int fehler,
                         int uebersprungen, LaufStatus status, String benutzer, Instant erstellt, Zuordnung zuordnung,
                         Zaehler vorschau, Uebernahme uebernahme) {

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
                status, benutzer, erstellt, null, null, null);
    }

    public ImportLauf(UUID id, ImportArt art, boolean vollexport, String dateiname, String sha256, String zeichensatz,
                      char trennzeichen, Kopfzeile kopf, int zeilen, int fehler, int uebersprungen, LaufStatus status,
                      String benutzer, Instant erstellt, Zuordnung zuordnung, Zaehler vorschau) {
        this(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen, fehler, uebersprungen,
                status, benutzer, erstellt, zuordnung, vorschau, null);
    }

    public ImportLauf mit(LaufStatus neuerStatus, int neueFehler, int neueUebersprungen) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen,
                neueFehler, neueUebersprungen, neuerStatus, benutzer, erstellt, zuordnung, vorschau, uebernahme);
    }

    public ImportLauf mit(Zuordnung neueZuordnung) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen, fehler,
                uebersprungen, status, benutzer, erstellt, neueZuordnung, vorschau, uebernahme);
    }

    /** Abgeschlossene Vorschau: Status {@code VORSCHAU} mit den Zählern. */
    public ImportLauf mitVorschau(Zaehler zaehler) {
        return new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, zeilen,
                zaehler.fehler(), zaehler.uebersprungen(), LaufStatus.VORSCHAU, benutzer, erstellt, zuordnung,
                zaehler, uebernahme);
    }

    /** Zahlen der Vorschau (Req 13.3): neu, geändert, unverändert, mögliche Dublette, fehlerhaft, übersprungen. */
    public record Zaehler(int neu, int geaendert, int unveraendert, int dubletten, int fehler, int uebersprungen) {
    }

    /**
     * Protokoll der Übernahme (Req 13.8): {@code von} hat sie ausgelöst; {@code zeit} und {@code bilanz} fehlen, bis
     * der Auftrag {@code CSV_UEBERNEHMEN} fertig ist.
     */
    public record Uebernahme(String von, Instant zeit, Bilanz bilanz) {
    }

    /**
     * Zähler der Übernahme: angelegt, aktualisiert, ausgelassen (Dubletten ohne bzw. mit Entscheid «überspringen»,
     * fehlerhafte und übersprungene Zeilen, Konflikte) und als «nicht mehr im CRM-Export» gekennzeichnet (Req 13.7).
     */
    public record Bilanz(int angelegt, int aktualisiert, int ausgelassen, int gekennzeichnet) {
    }
}
