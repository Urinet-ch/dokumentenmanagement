package ch.urinet.archiv.konfiguration.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Unveränderliches Abbild der fachlichen Konfiguration eines Mandanten. */
public record Konfiguration(int version, Einstellungen einstellungen, List<Art> arten) {

    /** Schema einer Art ohne Zusatzfelder. */
    public static final String OHNE_ZUSATZ = "{\"additionalProperties\":false,\"properties\":{},\"type\":\"object\"}";

    /** Die Arten stehen nach Code sortiert, damit Abbilder aus Migration und App gleich aufgebaut sind. */
    public Konfiguration {
        arten = arten.stream().sorted(Comparator.comparing(Art::code)).toList();
    }

    /** Liefert alle Prüfungen, die ohne die späteren Konfigurationsattribute möglich sind. */
    public List<Befund> pruefen() {
        List<Befund> befunde = new ArrayList<>();
        if (version < 1) {
            befunde.add(new Befund("version", "VERSION_UNGUELTIG", "Die Konfigurationsversion muss positiv sein."));
        }
        if (arten.size() > 200) {
            befunde.add(new Befund("arten", "ANZAHL_UNGUELTIG", "Die Konfiguration enthält zu viele Dokumentarten."));
        }
        Set<String> codes = new HashSet<>();
        Map<String, Art> nachCode = nachCode(arten);
        for (Art art : arten) {
            if (!codes.add(art.code())) {
                befunde.add(new Befund("arten." + art.code(), "DOKUMENTART_DOPPELT",
                        "Der Dokumentart-Code kommt mehrfach vor."));
            }
            if (art.belegnummerMuster().size() > 20 || art.belegnummerMuster().stream()
                    .anyMatch(muster -> !BelegnummerMuster.gueltig(muster))) {
                befunde.add(new Befund("arten." + art.code() + ".belegnummerMuster", "MUSTER_UNGUELTIG",
                        "Ein Belegnummer-Muster ist ungültig."));
            }
            artReferenzPruefen(befunde, nachCode, art, art.fremdbelegArt(), "fremdbelegArt");
            artReferenzPruefen(befunde, nachCode, art, art.ohneZahlungsdatenArt(), "ohneZahlungsdatenArt");
            if ("FELD".equals(art.aufbewahrung().ab())
                    ? art.aufbewahrung().feld() == null
                        || !art.pflichtfelder().contains("zusatz." + art.aufbewahrung().feld())
                    : art.aufbewahrung().feld() != null) {
                befunde.add(new Befund("arten." + art.code() + ".aufbewahrung.feld", "FRISTFELD_UNGUELTIG",
                        "Der Fristbezug FELD benötigt ein pflichtiges Zusatzfeld."));
            }
            wissensquellePruefen(befunde, art);
        }
        return List.copyOf(befunde);
    }

    private static void artReferenzPruefen(List<Befund> befunde, Map<String, Art> arten, Art quelle, String zielCode,
                                           String feld) {
        if (zielCode == null) {
            return;
        }
        Art ziel = arten.get(zielCode);
        if (ziel == null || !ziel.aktiv() || rang(ziel.vertraulichkeit()) < rang(quelle.vertraulichkeit())) {
            befunde.add(new Befund("arten." + quelle.code() + "." + feld, "ART_REFERENZ_UNGUELTIG",
                    "Die referenzierte Art fehlt, ist inaktiv oder weniger vertraulich."));
        }
    }

    private static int rang(String vertraulichkeit) {
        if (vertraulichkeit == null) {
            return -1;
        }
        return switch (vertraulichkeit) {
            case "NORMAL" -> 0;
            case "INTERN" -> 1;
            case "PERSONAL" -> 2;
            default -> -1;
        };
    }

    private static void wissensquellePruefen(List<Befund> befunde, Art art) {
        if (!"WISSENSQUELLE".equals(art.code())) {
            return;
        }
        if (!art.gruppen().equals(List.of("frei")) || !art.pflichtfelder().containsAll(List.of("titel", "datum"))
                || art.aufbewahrung().jahre() != null || art.aufbewahrung().ab() != null
                || art.gesetzlichePflicht() || art.kiVorschlag()) {
            befunde.add(new Befund("arten.WISSENSQUELLE", "WISSENSQUELLE_UNGUELTIG",
                    "Die festen Eigenschaften der Art WISSENSQUELLE dürfen nicht geändert werden."));
        }
    }

    public boolean gleicherInhalt(Konfiguration andere) {
        return andere != null && einstellungen.equals(andere.einstellungen) && arten.equals(andere.arten);
    }

    public List<Aenderung> aenderungen(Konfiguration neu) {
        List<Aenderung> aenderungen = new ArrayList<>();
        feld(aenderungen, "einstellungen.geschaeftsjahrEnde", einstellungen.geschaeftsjahrEnde(),
                neu.einstellungen.geschaeftsjahrEnde());
        feld(aenderungen, "einstellungen.kennungen.uid", einstellungen.kennungen().uid(),
                neu.einstellungen.kennungen().uid());
        feld(aenderungen, "einstellungen.kennungen.ibans", einstellungen.kennungen().ibans(),
                neu.einstellungen.kennungen().ibans());

        Map<String, Art> vorher = nachCode(arten);
        Map<String, Art> nachher = nachCode(neu.arten);
        Set<String> codes = new java.util.TreeSet<>(vorher.keySet());
        codes.addAll(nachher.keySet());
        for (String code : codes) {
            Art alt = vorher.get(code);
            Art aktuell = nachher.get(code);
            if (alt == null || aktuell == null) {
                feld(aenderungen, "arten." + code, alt, aktuell);
                continue;
            }
            String pfad = "arten." + code + ".";
            feld(aenderungen, pfad + "name", alt.name(), aktuell.name());
            feld(aenderungen, pfad + "beschreibung", alt.beschreibung(), aktuell.beschreibung());
            feld(aenderungen, pfad + "gruppen", alt.gruppen(), aktuell.gruppen());
            feld(aenderungen, pfad + "pflichtfelder", alt.pflichtfelder(), aktuell.pflichtfelder());
            feld(aenderungen, pfad + "zusatzSchema", alt.zusatzSchema(), aktuell.zusatzSchema());
            feld(aenderungen, pfad + "vertraulichkeit", alt.vertraulichkeit(), aktuell.vertraulichkeit());
            feld(aenderungen, pfad + "aufbewahrung.jahre", alt.aufbewahrung().jahre(),
                    aktuell.aufbewahrung().jahre());
            feld(aenderungen, pfad + "aufbewahrung.ab", alt.aufbewahrung().ab(), aktuell.aufbewahrung().ab());
            feld(aenderungen, pfad + "aufbewahrung.feld", alt.aufbewahrung().feld(), aktuell.aufbewahrung().feld());
            feld(aenderungen, pfad + "gesetzlichePflicht", alt.gesetzlichePflicht(), aktuell.gesetzlichePflicht());
            feld(aenderungen, pfad + "aktiv", alt.aktiv(), aktuell.aktiv());
            feld(aenderungen, pfad + "selbstAusgestellt", alt.selbstAusgestellt(), aktuell.selbstAusgestellt());
            feld(aenderungen, pfad + "belegnummerMuster", alt.belegnummerMuster(), aktuell.belegnummerMuster());
            feld(aenderungen, pfad + "fremdbelegArt", alt.fremdbelegArt(), aktuell.fremdbelegArt());
            feld(aenderungen, pfad + "ohneZahlungsdatenArt", alt.ohneZahlungsdatenArt(),
                    aktuell.ohneZahlungsdatenArt());
            feld(aenderungen, pfad + "kiVorschlag", alt.kiVorschlag(), aktuell.kiVorschlag());
            feld(aenderungen, pfad + "kiFelder", alt.kiFelder(), aktuell.kiFelder());
            feld(aenderungen, pfad + "kiHinweise", alt.kiHinweise(), aktuell.kiHinweise());
            feld(aenderungen, pfad + "reihenfolge", alt.reihenfolge(), aktuell.reihenfolge());
        }
        return List.copyOf(aenderungen);
    }

    private static Map<String, Art> nachCode(List<Art> arten) {
        Map<String, Art> ergebnis = new TreeMap<>();
        arten.forEach(art -> ergebnis.put(art.code(), art));
        return ergebnis;
    }

    private static void feld(List<Aenderung> aenderungen, String pfad, Object alt, Object neu) {
        if (!Objects.equals(alt, neu)) {
            aenderungen.add(new Aenderung(pfad, Objects.toString(alt, null), Objects.toString(neu, null)));
        }
    }

    public record Einstellungen(String geschaeftsjahrEnde, Kennungen kennungen) {
        public Einstellungen {
            kennungen = kennungen == null ? new Kennungen(null, List.of()) : kennungen;
        }
    }

    public record Art(String code, String name, String beschreibung, List<String> gruppen,
                      List<String> pflichtfelder, String zusatzSchema, String vertraulichkeit,
                      Aufbewahrung aufbewahrung, boolean gesetzlichePflicht, boolean aktiv,
                      boolean selbstAusgestellt, List<String> belegnummerMuster, String fremdbelegArt,
                      String ohneZahlungsdatenArt, boolean kiVorschlag, List<String> kiFelder,
                      String kiHinweise, int reihenfolge) {
        public Art(String code, String name, String beschreibung, List<String> gruppen,
                   List<String> pflichtfelder, String zusatzSchema, String vertraulichkeit,
                   Aufbewahrung aufbewahrung, boolean gesetzlichePflicht, boolean aktiv) {
            this(code, name, beschreibung, gruppen, pflichtfelder, zusatzSchema, vertraulichkeit, aufbewahrung,
                    gesetzlichePflicht, aktiv, false, List.of(), null, null, true, List.of(), null, 0);
        }

        public Art {
            gruppen = gruppen == null ? List.of() : List.copyOf(gruppen);
            pflichtfelder = pflichtfelder == null ? List.of() : List.copyOf(pflichtfelder);
            zusatzSchema = zusatzSchema == null || zusatzSchema.isBlank() ? OHNE_ZUSATZ : zusatzSchema;
            belegnummerMuster = belegnummerMuster == null ? List.of() : List.copyOf(belegnummerMuster);
            kiFelder = kiFelder == null ? List.of() : List.copyOf(kiFelder);
        }
    }

    public record Aufbewahrung(Integer jahre, String ab, String feld) {
        public Aufbewahrung(Integer jahre, String ab) {
            this(jahre, ab, null);
        }
    }

    public record Kennungen(String uid, List<String> ibans) {
        public Kennungen {
            ibans = ibans == null ? List.of() : List.copyOf(ibans);
        }
    }

    public record Aenderung(String pfad, String alt, String neu) {
        /** Kennungen (UID, IBAN) nie im Klartext ausgeben (Spec 01, 16.7). */
        @Override
        public String toString() {
            boolean kennung = pfad != null && pfad.startsWith("einstellungen.kennungen.");
            return kennung ? "Aenderung[pfad=" + pfad + ", geändert]"
                    : "Aenderung[pfad=" + pfad + ", alt=" + alt + ", neu=" + neu + "]";
        }
    }

    public record Befund(String pfad, String code, String meldung) {
    }
}
