package ch.urinet.archiv.importe.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spaltenzuordnung «Feld → Spaltenposition» (0-basiert) einer Importart, optional mit dem Profil «21.CRM» (design.md
 * «Kontakt und Importe»). Gespeichert wird nach Position, weil Spaltennamen doppelt vorkommen können. Die Regeln für
 * Name, E-Mail und Telefon wirken, sobald ihre Hilfsfelder zugeordnet sind; das Profil setzt sie alle.
 */
public record Zuordnung(String profil, Map<String, Integer> felder) {

    public static final String PROFIL_21CRM = "21.CRM";

    public Zuordnung {
        if (profil != null && !PROFIL_21CRM.equals(profil)) {
            throw new IllegalArgumentException("unbekanntes Profil");
        }
        felder = Map.copyOf(felder);
    }

    public Optional<String> profilName() {
        return Optional.ofNullable(profil);
    }

    /** Mangel der Zuordnung gegenüber einer Kopfzeile mit {@code spalten} Spalten; leer, wenn sie gilt. */
    public Optional<Mangel> mangel(ImportArt art, int spalten) {
        for (Map.Entry<String, Integer> eintrag : felder.entrySet()) {
            if (!ImportFeld.erlaubt(art).contains(eintrag.getKey())) {
                return Optional.of(Mangel.UNBEKANNTES_FELD);
            }
            if (eintrag.getValue() < 0 || eintrag.getValue() >= spalten) {
                return Optional.of(Mangel.POSITION_UNGUELTIG);
            }
        }
        boolean pflicht = art == ImportArt.KONTAKT
                ? felder.containsKey(ImportFeld.NAME) || felder.containsKey(ImportFeld.ORGANISATION)
                : felder.containsKey(art.pflichtfeld());
        return pflicht ? Optional.empty() : Optional.of(Mangel.PFLICHTFELD_FEHLT);
    }

    /** Die Zeile ist im CRM gelöscht ({@code geloescht = true}) und wird übersprungen. */
    public boolean geloescht(List<String> werte) {
        return wahr(wert(werte, ImportFeld.GELOESCHT));
    }

    /**
     * Feldwerte einer Zeile: {@code name} bei Organisationen ({@code typ = true}) aus {@code organisation}, sonst aus
     * {@code name} (ohne Spalte {@code name} aus {@code organisation}); {@code email} und {@code telefon} aus der eigenen
     * Spalte oder nach Index mit Ausweichreihenfolge (Profil «21.CRM»). Leere Werte fehlen in der Abbildung, Hilfsfelder
     * erscheinen nicht. Werte bleiben unverändert bis auf Leerraum am Rand, auch wenn sie mit {@code =}, {@code +},
     * {@code -} oder {@code @} beginnen (Telefonnummern beginnen mit {@code +}); ein Export neutralisiert sie.
     */
    public Map<String, String> werte(ImportArt art, List<String> werte) {
        Map<String, String> ergebnis = new LinkedHashMap<>();
        for (String feld : ImportFeld.erlaubt(art)) {
            if (!ImportFeld.HILFSFELDER.contains(feld)) {
                setzen(ergebnis, feld, wert(werte, feld));
            }
        }
        if (art != ImportArt.KONTAKT) {
            return ergebnis;
        }
        boolean organisation = wahr(wert(werte, ImportFeld.TYP));
        if (felder.containsKey(ImportFeld.TYP)) {
            ergebnis.put(ImportFeld.TYP, organisation ? "ORGANISATION" : "PERSON");
        }
        ergebnis.remove(ImportFeld.NAME);
        setzen(ergebnis, ImportFeld.NAME, organisation || !felder.containsKey(ImportFeld.NAME)
                ? wert(werte, ImportFeld.ORGANISATION) : wert(werte, ImportFeld.NAME));
        if (!felder.containsKey(ImportFeld.EMAIL)) {
            setzen(ergebnis, ImportFeld.EMAIL, nachIndex(werte, ImportFeld.STANDARD_EMAIL,
                    Map.of("1", ImportFeld.EMAIL_GESCHAEFT, "3", ImportFeld.EMAIL_PRIVAT, "5", ImportFeld.EMAIL_ZENTRALE),
                    List.of(ImportFeld.EMAIL_GESCHAEFT, ImportFeld.EMAIL_ZENTRALE, ImportFeld.EMAIL_GESCHAEFT_2,
                            ImportFeld.EMAIL_PRIVAT)));
        }
        if (!felder.containsKey(ImportFeld.TELEFON)) {
            setzen(ergebnis, ImportFeld.TELEFON, nachIndex(werte, ImportFeld.STANDARD_TELEFON,
                    Map.of("4", ImportFeld.TELEFON_GESCHAEFT, "10", ImportFeld.TELEFON_ZENTRALE),
                    List.of(ImportFeld.TELEFON_GESCHAEFT, ImportFeld.TELEFON_ZENTRALE, ImportFeld.MOBIL_GESCHAEFT)));
        }
        return ergebnis;
    }

    private String nachIndex(List<String> werte, String indexFeld, Map<String, String> index, List<String> ausweichen) {
        String gewaehlt = index.get(wert(werte, indexFeld));
        if (gewaehlt != null && !wert(werte, gewaehlt).isEmpty()) {
            return wert(werte, gewaehlt);
        }
        return ausweichen.stream().map(f -> wert(werte, f)).filter(w -> !w.isEmpty()).findFirst().orElse("");
    }

    private String wert(List<String> werte, String feld) {
        Integer position = felder.get(feld);
        if (position == null || position >= werte.size()) {
            return "";
        }
        String wert = werte.get(position);
        return wert == null ? "" : wert.strip();
    }

    private static void setzen(Map<String, String> ergebnis, String feld, String wert) {
        if (!wert.isEmpty()) {
            ergebnis.put(feld, wert);
        }
    }

    private static boolean wahr(String wert) {
        return "true".equalsIgnoreCase(wert);
    }

    /** Warum eine Zuordnung nicht gilt. */
    public enum Mangel {
        UNBEKANNTES_FELD,
        POSITION_UNGUELTIG,
        /** keine Spalte für {@code name} (bzw. {@code organisation}) oder {@code titel} (Req 13.10) */
        PFLICHTFELD_FEHLT
    }
}
