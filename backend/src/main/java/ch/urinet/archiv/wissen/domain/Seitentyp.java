package ch.urinet.archiv.wissen.domain;

import java.util.Arrays;
import java.util.Optional;

public enum Seitentyp {
    SYNTHESE("Synthese", null),
    QUELLE("Quelle", "quellen"),
    KONZEPT("Konzept", "konzepte"),
    AKTEUR("Akteur", "akteure"),
    PROJEKT("Projekt", "projekte"),
    ANALYSE("Analyse", "analysen");

    private final String datenbankwert;
    private final String ordner;

    Seitentyp(String datenbankwert, String ordner) {
        this.datenbankwert = datenbankwert;
        this.ordner = ordner;
    }

    public String datenbankwert() {
        return datenbankwert;
    }

    public Optional<String> ordner() {
        return Optional.ofNullable(ordner);
    }

    public static Optional<Seitentyp> vonDatenbankwert(String wert) {
        return Arrays.stream(values()).filter(typ -> typ.datenbankwert.equals(wert)).findFirst();
    }
}
