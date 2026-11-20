package ch.urinet.archiv.dokumentart.application;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.time.LocalDate;

/**
 * Prüft {@code zusatz_schema} (JSON Schema Draft 2020-12) und Zusatzwerte dagegen (Req 9.5). Fehler ergeben
 * {@code FachFehler(ZUSATZ_UNGUELTIG)}. Die Prüfung löst nie einen Netzwerkzugriff aus: Schemas mit externen
 * {@code $ref}/{@code $id} sind ungültig (SSRF).
 */
public interface ZusatzSchemaPruefer {

    /** Prüft das Schema und liefert die Namen der Zusatzfelder ({@code properties} der obersten Ebene). */
    Set<String> schemaPruefen(String schemaJson);

    /** JSON-Typen der direkten Schemafelder; {@code format: date} wird als {@code date} ausgewiesen. */
    Map<String, String> feldTypen(String schemaJson);

    /** Beschreibungen der direkten Schemafelder, sofern sie gesetzt sind. */
    default Map<String, String> feldBeschreibungen(String schemaJson) {
        return Map.of();
    }

    /**
     * Wie {@link #schemaPruefen}, aber für den Seed eines Mandanten (Admin-Kommando, Quelle im Repo) mit der eigenen,
     * grösseren Frist {@code archiv.dokumentart.seed-zeitgrenze}.
     */
    Set<String> seedSchemaPruefen(String schemaJson);

    /** Prüft Zusatzwerte (JSON-Objekt) gegen ein gültiges Schema. */
    void wertePruefen(String schemaJson, String werteJson);

    /**
     * Prüft eine Konfigurationsdatei bis 1 MiB gegen das gespeicherte Schema und liefert höchstens
     * {@link #MAX_VERLETZUNGEN} Verletzungen (leer = gültig). Ist die Datei kein lesbares JSON-Objekt, folgt
     * {@code FachFehler(ZUSATZ_UNGUELTIG)}.
     */
    List<Verletzung> konfigurationPruefen(String schemaJson, String dateiJson);

    int MAX_VERLETZUNGEN = 100;

    /**
     * Eine Schemaverletzung ohne Werte aus der Datei: JSON-Pointer (Namen, die kein einfacher Bezeichner sind,
     * erscheinen als {@code *}) und das verletzte Schlüsselwort.
     */
    record Verletzung(String pfad, String schluesselwort) {
    }

    /** Liest ein bereits geprüftes Zusatzobjekt als unveränderliche Werte für deklarative Regeln. */
    Map<String, Object> werteLesen(String werteJson);

    /** Prüft die festen Definitionen der reservierten Felder, sofern sie im Schema vorkommen. */
    boolean reservierteFelderGueltig(String schemaJson);

    boolean datumsfeldGueltig(String schemaJson, String feld);

    /** Fehlend, null oder leer ergibt kein Datum; andere ungültige Werte ergeben ZUSATZ_UNGUELTIG. */
    LocalDate datumswert(String werteJson, String feld);
}
