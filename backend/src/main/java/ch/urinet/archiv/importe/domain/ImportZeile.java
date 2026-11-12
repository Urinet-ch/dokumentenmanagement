package ch.urinet.archiv.importe.domain;

import java.util.List;
import java.util.Objects;

/**
 * Eine Datenzeile eines Laufs ({@code import_zeile}) mit den Rohwerten in Spaltenreihenfolge und dem Ergebnis der
 * Prüfung. {@code werte} fehlt, wenn ein Feld die Feldgrenze oder die Zeile {@link CsvTabelle#MAX_ZEILE} überschreitet:
 * Solche Werte werden nicht gespeichert.
 */
public record ImportZeile(int nr, List<String> werte, ZeilenPruefung.Ergebnis ergebnis, String meldung) {

    public ImportZeile {
        Objects.requireNonNull(ergebnis, "ergebnis");
        werte = werte == null ? null : List.copyOf(werte);
    }
}
