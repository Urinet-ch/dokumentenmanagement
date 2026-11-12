package ch.urinet.archiv.dokumentart.application;

import java.util.Set;

/**
 * Prüft {@code zusatz_schema} (JSON Schema Draft 2020-12) und Zusatzwerte dagegen (Req 9.5). Fehler ergeben
 * {@code FachFehler(ZUSATZ_UNGUELTIG)}. Die Prüfung löst nie einen Netzwerkzugriff aus: Schemas mit externen
 * {@code $ref}/{@code $id} sind ungültig (SSRF).
 */
public interface ZusatzSchemaPruefer {

    /** Prüft das Schema und liefert die Namen der Zusatzfelder ({@code properties} der obersten Ebene). */
    Set<String> schemaPruefen(String schemaJson);

    /**
     * Wie {@link #schemaPruefen}, aber für den Seed eines Mandanten (Admin-Kommando, Quelle im Repo) mit der eigenen,
     * grösseren Frist {@code archiv.dokumentart.seed-zeitgrenze}.
     */
    Set<String> seedSchemaPruefen(String schemaJson);

    /** Prüft Zusatzwerte (JSON-Objekt) gegen ein gültiges Schema. */
    void wertePruefen(String schemaJson, String werteJson);
}
