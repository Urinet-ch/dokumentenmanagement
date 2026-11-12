package ch.urinet.archiv.importe.domain;

import java.util.Arrays;
import java.util.Optional;

/** Importart des CSV-Imports (Req 13.2, 13.9); der Code steht in {@code import_lauf.art} und im Query-Parameter. */
public enum ImportArt {
    KONTAKT("kontakt", "name"),
    VERKAUFSCHANCE("verkaufschance", "titel");

    private final String code;
    private final String pflichtfeld;

    ImportArt(String code, String pflichtfeld) {
        this.code = code;
        this.pflichtfeld = pflichtfeld;
    }

    public String code() {
        return code;
    }

    /** Feld, ohne dessen Spalte die Zuordnung abgelehnt wird und ohne dessen Wert eine Zeile «fehlerhaft» ist (Req 13.10). */
    public String pflichtfeld() {
        return pflichtfeld;
    }

    public static Optional<ImportArt> aus(String code) {
        return Arrays.stream(values()).filter(a -> a.code.equals(code)).findFirst();
    }
}
