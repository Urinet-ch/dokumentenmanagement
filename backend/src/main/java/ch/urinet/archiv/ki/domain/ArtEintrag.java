package ch.urinet.archiv.ki.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Für die Erfassung relevante, mandantenspezifische Eigenschaften einer Dokumentart. */
public record ArtEintrag(String code, String name, String beschreibung, String vertraulichkeit,
                         List<String> gruppen, boolean selbstAusgestellt, boolean kiVorschlag,
                         List<String> belegnummerMuster,
                         String fremdbelegArt, String ohneZahlungsdatenArt, List<String> kiFelder,
                         String kiHinweise, int reihenfolge, String zusatzSchema,
                         Map<String, String> zusatzFeldTypen, List<String> klassifikationen,
                         Map<String, String> zusatzFeldBeschreibungen) {

    public ArtEintrag {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(vertraulichkeit, "vertraulichkeit");
        gruppen = gruppen == null ? List.of() : List.copyOf(gruppen);
        belegnummerMuster = belegnummerMuster == null ? List.of() : List.copyOf(belegnummerMuster);
        kiFelder = kiFelder == null ? List.of() : List.copyOf(kiFelder);
        zusatzFeldTypen = zusatzFeldTypen == null ? Map.of() : Map.copyOf(zusatzFeldTypen);
        klassifikationen = klassifikationen == null ? List.of() : List.copyOf(klassifikationen);
        zusatzFeldBeschreibungen = zusatzFeldBeschreibungen == null ? Map.of()
                : Map.copyOf(zusatzFeldBeschreibungen);
    }

    public ArtEintrag(String code, String name, String beschreibung, String vertraulichkeit, List<String> gruppen,
                      boolean selbstAusgestellt, boolean kiVorschlag, List<String> belegnummerMuster,
                      String fremdbelegArt, String ohneZahlungsdatenArt, List<String> kiFelder, String kiHinweise,
                      int reihenfolge, String zusatzSchema, Map<String, String> zusatzFeldTypen,
                      List<String> klassifikationen) {
        this(code, name, beschreibung, vertraulichkeit, gruppen, selbstAusgestellt, kiVorschlag, belegnummerMuster,
                fremdbelegArt, ohneZahlungsdatenArt, kiFelder, kiHinweise, reihenfolge, zusatzSchema, zusatzFeldTypen,
                klassifikationen, Map.of());
    }

    public ArtEintrag(String code, String name, String vertraulichkeit) {
        this(code, name, null, vertraulichkeit, List.of(), false, true, List.of(), null, null, List.of(), null, 0,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", Map.of(), List.of(),
                Map.of());
    }
}
