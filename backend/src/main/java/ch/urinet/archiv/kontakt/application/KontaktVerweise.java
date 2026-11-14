package ch.urinet.archiv.kontakt.application;

import java.util.List;
import java.util.UUID;

/**
 * Driven Port: Verweise auf einen Kontakt im Schema des Mandanten (Req 17.7; design.md «Kontakte ansehen und pflegen»,
 * Schritt 4). Jede Spalte unter {@link #UMGEHAENGT} hängt das Zusammenführen um, die unter {@link #AUSGENOMMEN} nie;
 * {@code KontaktVerweiseTest} verlangt, dass jede Spalte des Mandanten-Schemas, die auf einen Kontakt zeigen kann, in
 * einer der beiden Listen steht. {@code dokument.partner_id} hängt {@code DokumentAenderungPort} um (mit Version und
 * {@code dokument_version} wie eine Änderung des Partners), die übrigen dieser Port.
 */
public interface KontaktVerweise {

    /** Umgehängte Spalten ({@code tabelle.spalte}). */
    List<String> UMGEHAENGT = List.of("dokument.partner_id", "dokument.ki_partner_id", "verkaufschance.kontakt_id",
            "ki_beispiel.partner_id");

    /**
     * Nie umgehängt: der Verlauf eines Import-Laufs (die Übernahme behandelt inaktive Kontakte nach Req 17.14), die
     * Kette der Zusammenführungen selbst; {@code zugriff_protokoll} und {@code dokument_version} sind nur Einfügen.
     */
    List<String> AUSGENOMMEN = List.of("import_zeile.kontakt_id", "import_zeile.entscheid_kontakt",
            "kontakt.zusammengefuehrt_in");

    /** Zahlen der Verweise über alle Dokumente des Mandanten. */
    record Zahlen(int dokumente, int verkaufschancen, int korrekturbeispiele) {
    }

    /** Wie viele Dokumente, Verkaufschancen und Korrekturbeispiele auf den Kontakt zeigen (Req 17.17). */
    Zahlen zaehlen(UUID kontakt);

    /**
     * Hängt Verkaufschancen, Korrekturbeispiele und den Partner des KI-Vorschlags ({@code dokument.ki_partner_id}, kein
     * Feld des Eingangskorbs, ohne neue Version) von {@code quelle} an {@code ziel}, je mit einer Mengenanweisung, in
     * der Transaktion des Aufrufers. {@code dokumente} der Antwort ist 0 (die hängt {@code DokumentAenderungPort} um).
     */
    Zahlen umhaengen(UUID quelle, UUID ziel);
}
