package ch.urinet.archiv.mandant.domain;

import java.util.List;

/** Eine Dokumentart aus dem Konfigurationsseed des Mandanten, Spalten wie {@code dokumentart}. */
public record DokumentartVorlage(String code, String name, String beschreibung, List<String> gruppen, List<String> pflichtfelder,
                                 String zusatzSchemaJson, String vertraulichkeit, Integer aufbewahrungJahre,
                                 String aufbewahrungAb, boolean gesetzlichePflicht, boolean aktiv,
                                 boolean selbstAusgestellt, List<String> belegnummerMuster, String fremdbelegArt,
                                 String ohneZahlungsdatenArt, boolean kiVorschlag, List<String> kiFelder,
                                 String kiHinweise, int reihenfolge, String aufbewahrungFeld) {

    public DokumentartVorlage(String code, String name, String beschreibung, List<String> gruppen,
                              List<String> pflichtfelder, String zusatzSchemaJson, String vertraulichkeit,
                              Integer aufbewahrungJahre, String aufbewahrungAb, boolean gesetzlichePflicht,
                              boolean aktiv) {
        this(code, name, beschreibung, gruppen, pflichtfelder, zusatzSchemaJson, vertraulichkeit, aufbewahrungJahre,
                aufbewahrungAb, gesetzlichePflicht, aktiv, false, List.of(), null, null, true, List.of(), null, 0, null);
    }

    public DokumentartVorlage(String code, String name, String beschreibung, List<String> gruppen,
                              List<String> pflichtfelder, String zusatzSchemaJson, String vertraulichkeit,
                              Integer aufbewahrungJahre, String aufbewahrungAb, boolean gesetzlichePflicht,
                              boolean aktiv, boolean selbstAusgestellt, List<String> belegnummerMuster,
                              String fremdbelegArt, String ohneZahlungsdatenArt, boolean kiVorschlag,
                              List<String> kiFelder, String kiHinweise, int reihenfolge) {
        this(code, name, beschreibung, gruppen, pflichtfelder, zusatzSchemaJson, vertraulichkeit, aufbewahrungJahre,
                aufbewahrungAb, gesetzlichePflicht, aktiv, selbstAusgestellt, belegnummerMuster, fremdbelegArt,
                ohneZahlungsdatenArt, kiVorschlag, kiFelder, kiHinweise, reihenfolge, null);
    }

    public DokumentartVorlage {
        gruppen = List.copyOf(gruppen);
        pflichtfelder = List.copyOf(pflichtfelder);
        belegnummerMuster = List.copyOf(belegnummerMuster);
        kiFelder = List.copyOf(kiFelder);
    }
}
