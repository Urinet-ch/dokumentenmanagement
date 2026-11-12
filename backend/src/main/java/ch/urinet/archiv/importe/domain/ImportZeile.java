package ch.urinet.archiv.importe.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Eine Datenzeile eines Laufs ({@code import_zeile}) mit den Rohwerten in Spaltenreihenfolge und dem Ergebnis der
 * Prüfung. {@code werte} fehlt, wenn ein Feld die Feldgrenze oder die Zeile {@link CsvTabelle#MAX_ZEILE} überschreitet:
 * Solche Werte werden nicht gespeichert. {@code kontaktId} (Treffer bei {@code GEAENDERT}/{@code UNVERAENDERT}) und
 * {@code kandidaten} (bei {@code DUBLETTE}) setzt die Vorschau (Aufgabe 4.2).
 */
public record ImportZeile(int nr, List<String> werte, ZeilenPruefung.Ergebnis ergebnis, String meldung, UUID kontaktId,
                          List<UUID> kandidaten) {

    public ImportZeile {
        Objects.requireNonNull(ergebnis, "ergebnis");
        werte = werte == null ? null : List.copyOf(werte);
        kandidaten = kandidaten == null ? List.of() : List.copyOf(kandidaten);
    }

    public ImportZeile(int nr, List<String> werte, ZeilenPruefung.Ergebnis ergebnis, String meldung) {
        this(nr, werte, ergebnis, meldung, null, List.of());
    }

    /** Dieselbe Zeile mit dem Befund des Abgleichs. */
    public ImportZeile mit(KontaktAbgleich.Befund befund) {
        return new ImportZeile(nr, werte, befund.ergebnis(), befund.meldung(), befund.kontaktId(),
                befund.kandidaten());
    }
}
