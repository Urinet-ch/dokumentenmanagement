package ch.urinet.archiv.konfiguration.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Unveränderliches Abbild der fachlichen Konfiguration eines Mandanten. */
public record Konfiguration(int version, Einstellungen einstellungen, List<Art> arten,
                            List<Klassifikation> klassifikationen) {

    /** Schema einer Art ohne Zusatzfelder. */
    public static final String OHNE_ZUSATZ = "{\"additionalProperties\":false,\"properties\":{},\"type\":\"object\"}";
    private static final Pattern KLASSIFIKATIONSCODE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Set<String> RESERVIERTE_PUBLIC_PARAMETER = Set.of("q", "reihe", "jahr", "ausgabe", "seite",
            "pro_seite", "dateiname", "id", "sortierung", "gespraech_id", "zugriff", "veroeffentlicht", "seit", "max",
            "referenz", "groesse", "status", "url");

    public Konfiguration(int version, Einstellungen einstellungen, List<Art> arten) {
        this(version, einstellungen, arten, List.of());
    }

    /** Die Arten stehen nach Code sortiert, damit Abbilder aus Migration und App gleich aufgebaut sind. */
    public Konfiguration {
        arten = arten == null ? List.of() : arten.stream().sorted(Comparator.comparing(Art::code)).toList();
        klassifikationen = klassifikationen == null ? List.of()
                : klassifikationen.stream()
                        .sorted(Comparator.comparing(Klassifikation::code, Comparator.nullsFirst(String::compareTo)))
                        .toList();
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
        Map<String, Klassifikation> klassifikationenNachCode = new TreeMap<>();
        if (klassifikationen.size() > 50) {
            befunde.add(new Befund("klassifikationen", "ANZAHL_UNGUELTIG",
                    "Die Konfiguration enthält zu viele Klassifikationen."));
        }
        for (Klassifikation klassifikation : klassifikationen) {
            String code = klassifikation.code();
            if (code == null || !KLASSIFIKATIONSCODE.matcher(code).matches()) {
                befunde.add(new Befund("klassifikationen", "CODE_UNGUELTIG",
                        "Ein Klassifikations-Code ist ungültig."));
            } else if (klassifikationenNachCode.putIfAbsent(code, klassifikation) != null) {
                befunde.add(new Befund("klassifikationen." + code, "CODE_DOPPELT",
                        "Der Klassifikations-Code kommt mehrfach vor."));
            }
            if (!text(klassifikation.name(), 100) || !optionalerText(klassifikation.beschreibung(), 1000)) {
                befunde.add(new Befund("klassifikationen." + code, "TEXT_UNGUELTIG",
                        "Name oder Beschreibung der Klassifikation ist ungültig."));
            }
            if ("zugriff".equals(code) || "veroeffentlicht".equals(code)
                    || klassifikation.oeffentlichFilter() && RESERVIERTE_PUBLIC_PARAMETER.contains(code)) {
                befunde.add(new Befund("klassifikationen." + code, "PARAMETER_RESERVIERT",
                        "Der Klassifikations-Code ist für ein reserviertes Feld oder einen reservierten Parameter belegt."));
            }
            if (klassifikation.werte().size() > 500) {
                befunde.add(new Befund("klassifikationen." + code + ".werte", "ANZAHL_UNGUELTIG",
                        "Die Klassifikation enthält zu viele Werte."));
            }
            Set<String> wertCodes = new HashSet<>();
            for (KlassifikationWert wert : klassifikation.werte()) {
                String wertCode = wert.code();
                if (wertCode == null || !KLASSIFIKATIONSCODE.matcher(wertCode).matches()) {
                    befunde.add(new Befund("klassifikationen." + code + ".werte", "CODE_UNGUELTIG",
                            "Ein Wert-Code ist ungültig."));
                } else if (!wertCodes.add(wertCode)) {
                    befunde.add(new Befund("klassifikationen." + code + ".werte." + wertCode, "CODE_DOPPELT",
                            "Der Wert-Code kommt mehrfach vor."));
                }
                if (!text(wert.name(), 100) || !optionalerText(wert.beschreibung(), 1000)) {
                    befunde.add(new Befund("klassifikationen." + code + ".werte." + wertCode, "TEXT_UNGUELTIG",
                            "Name oder Beschreibung des Werts ist ungültig."));
                }
            }
        }
        for (Art art : arten) {
            Set<String> zugeordnete = new HashSet<>();
            for (ArtKlassifikation zuordnung : art.klassifikationen()) {
                Klassifikation klassifikation = klassifikationenNachCode.get(zuordnung.klassifikation());
                if (!zugeordnete.add(zuordnung.klassifikation()) || klassifikation == null) {
                    befunde.add(new Befund("arten." + art.code() + ".klassifikationen",
                            "KLASSIFIKATION_UNGUELTIG", "Eine Klassifikation der Art fehlt oder ist doppelt zugeordnet."));
                    continue;
                }
                if (!klassifikation.aktiv() && (zuordnung.pflicht() || zuordnung.standardWert() != null)) {
                    befunde.add(new Befund("arten." + art.code() + ".klassifikationen."
                            + zuordnung.klassifikation(), "KLASSIFIKATION_UNGUELTIG",
                            "Eine inaktive Klassifikation darf in der Art weder Pflicht sein noch einen Standardwert tragen."));
                }
                if (zuordnung.standardWert() != null && klassifikation.werte().stream()
                        .noneMatch(wert -> wert.code().equals(zuordnung.standardWert()) && wert.aktiv())) {
                    befunde.add(new Befund("arten." + art.code() + ".klassifikationen."
                            + zuordnung.klassifikation(), "STANDARDWERT_UNGUELTIG",
                            "Der Standardwert fehlt oder ist inaktiv."));
                }
            }
        }
        return List.copyOf(befunde);
    }

    public List<String> ungueltigeDokumentKlassifikationen(String artCode, Map<String, List<String>> werte,
                                                           Map<String, List<String>> vorher) {
        return dokumentKlassifikationenPruefen(artCode, werte, vorher, true);
    }

    /** Wie {@link #ungueltigeDokumentKlassifikationen}, aber ohne fehlende Pflicht-Klassifikationen (Req 1.7). */
    public List<String> veralteteDokumentKlassifikationen(String artCode, Map<String, List<String>> werte,
                                                          Map<String, List<String>> vorher) {
        return dokumentKlassifikationenPruefen(artCode, werte, vorher, false);
    }

    private List<String> dokumentKlassifikationenPruefen(String artCode, Map<String, List<String>> werte,
                                                         Map<String, List<String>> vorher, boolean pflichtPruefen) {
        // Ohne Art oder mit einer Art, die der Stand nicht kennt, ist nichts zugeordnet: jeder gesetzte Code ist
        // ungültig (Req 3.5). Ob die Art existiert, prüfen die Aufrufer selbst.
        Art art = artCode == null ? null
                : arten.stream().filter(eintrag -> artCode.equals(eintrag.code())).findFirst().orElse(null);
        Map<String, Klassifikation> klassifikationenNachCode = new TreeMap<>();
        klassifikationen.forEach(klassifikation ->
                klassifikationenNachCode.put(klassifikation.code(), klassifikation));
        Map<String, ArtKlassifikation> zuordnungen = new TreeMap<>();
        if (art != null) {
            art.klassifikationen().forEach(zuordnung -> zuordnungen.put(zuordnung.klassifikation(), zuordnung));
        }
        Map<String, List<String>> gesetzt = werte == null ? Map.of() : werte;
        Map<String, List<String>> alt = vorher == null ? Map.of() : vorher;
        Set<String> ungueltig = new TreeSet<>();

        for (Map.Entry<String, List<String>> eintrag : gesetzt.entrySet()) {
            String code = eintrag.getKey();
            String sichererCode = code != null && KLASSIFIKATIONSCODE.matcher(code).matches()
                    ? code : "klassifikation";
            Klassifikation klassifikation = klassifikationenNachCode.get(code);
            ArtKlassifikation zuordnung = zuordnungen.get(code);
            List<String> liste = eintrag.getValue();
            if (zuordnung == null || klassifikation == null || liste == null || liste.size() > 500) {
                ungueltig.add(sichererCode);
                continue;
            }
            Set<String> neueWerte = new HashSet<>(liste);
            Set<String> alteWerte = new HashSet<>(alt.getOrDefault(code, List.of()));
            if (!klassifikation.aktiv() && !alteWerte.containsAll(neueWerte)
                    || !klassifikation.mehrfach() && liste.size() > 1
                    || zuordnung.pflicht() && liste.isEmpty()
                    || neueWerte.size() != liste.size()) {
                ungueltig.add(sichererCode);
                continue;
            }
            Set<String> aktiveWerte = new HashSet<>();
            klassifikation.werte().stream().filter(KlassifikationWert::aktiv)
                    .map(KlassifikationWert::code).forEach(aktiveWerte::add);
            for (String wert : liste) {
                if (!aktiveWerte.contains(wert) && !alteWerte.contains(wert)) {
                    ungueltig.add(sichererCode);
                    break;
                }
            }
        }

        for (ArtKlassifikation zuordnung : zuordnungen.values()) {
            if (pflichtPruefen && zuordnung.pflicht() && gesetzt.getOrDefault(zuordnung.klassifikation(), List.of()).isEmpty()) {
                ungueltig.add(zuordnung.klassifikation());
            }
        }
        return List.copyOf(ungueltig);
    }

    public Map<String, List<String>> beimArtwechselVorbelegen(String artCode, Map<String, List<String>> bisher) {
        Art art = arten.stream().filter(eintrag -> artCode != null && artCode.equals(eintrag.code()))
                .findFirst().orElse(null);
        if (art == null) {
            return Map.of();
        }
        Map<String, List<String>> vorhanden = bisher == null ? Map.of() : bisher;
        Map<String, List<String>> erlaubt = new TreeMap<>();
        for (ArtKlassifikation zuordnung : art.klassifikationen()) {
            List<String> werte = vorhanden.getOrDefault(zuordnung.klassifikation(), List.of());
            if (werte.isEmpty() && zuordnung.standardWert() != null) {
                werte = List.of(zuordnung.standardWert());
            }
            if (!werte.isEmpty()) {
                erlaubt.put(zuordnung.klassifikation(), List.copyOf(werte));
            }
        }
        return Map.copyOf(erlaubt);
    }

    private static boolean text(String wert, int max) {
        return wert != null && !wert.isBlank() && wert.length() <= max
                && wert.codePoints().noneMatch(c -> Character.getType(c) == Character.CONTROL);
    }

    /** Beschreibungen dürfen Zeilenumbrüche und Tabulatoren enthalten, sonst keine Steuerzeichen. */
    private static boolean optionalerText(String wert, int max) {
        return wert == null || wert.length() <= max && wert.codePoints().noneMatch(c ->
                Character.getType(c) == Character.CONTROL && c != '\n' && c != '\r' && c != '\t');
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
        return andere != null && einstellungen.equals(andere.einstellungen) && arten.equals(andere.arten)
                && klassifikationen.equals(andere.klassifikationen);
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
            Map<String, ArtKlassifikation> alteZuordnungen = artZuordnungen(alt.klassifikationen());
            Map<String, ArtKlassifikation> neueZuordnungen = artZuordnungen(aktuell.klassifikationen());
            Set<String> klassen = new java.util.TreeSet<>(alteZuordnungen.keySet());
            klassen.addAll(neueZuordnungen.keySet());
            for (String klassifikation : klassen) {
                ArtKlassifikation vorherige = alteZuordnungen.get(klassifikation);
                ArtKlassifikation neue = neueZuordnungen.get(klassifikation);
                String zuordnungspfad = pfad + "klassifikationen." + klassifikation;
                feld(aenderungen, zuordnungspfad, vorhanden(vorherige), vorhanden(neue));
                if (vorherige != null && neue != null) {
                    feld(aenderungen, zuordnungspfad + ".pflicht", vorherige.pflicht(), neue.pflicht());
                    feld(aenderungen, zuordnungspfad + ".standardWert", vorherige.standardWert(),
                            neue.standardWert());
                }
            }
        }
        klassifikationAenderungen(aenderungen, klassifikationen, neu.klassifikationen);
        return List.copyOf(aenderungen);
    }

    private static Map<String, ArtKlassifikation> artZuordnungen(List<ArtKlassifikation> zuordnungen) {
        Map<String, ArtKlassifikation> ergebnis = new TreeMap<>();
        zuordnungen.forEach(zuordnung -> ergebnis.put(zuordnung.klassifikation(), zuordnung));
        return ergebnis;
    }

    private static void klassifikationAenderungen(List<Aenderung> aenderungen, List<Klassifikation> alt,
                                                  List<Klassifikation> neu) {
        Map<String, Klassifikation> vorher = new TreeMap<>();
        alt.forEach(k -> vorher.put(k.code(), k));
        Map<String, Klassifikation> nachher = new TreeMap<>();
        neu.forEach(k -> nachher.put(k.code(), k));
        Set<String> codes = new java.util.TreeSet<>(vorher.keySet());
        codes.addAll(nachher.keySet());
        for (String code : codes) {
            Klassifikation a = vorher.get(code);
            Klassifikation n = nachher.get(code);
            String pfad = "klassifikationen." + code;
            feld(aenderungen, pfad, vorhanden(a), vorhanden(n));
            if (a == null || n == null) {
                continue;
            }
            feld(aenderungen, pfad + ".name", a.name(), n.name());
            feld(aenderungen, pfad + ".beschreibung", a.beschreibung(), n.beschreibung());
            feld(aenderungen, pfad + ".mehrfach", a.mehrfach(), n.mehrfach());
            feld(aenderungen, pfad + ".kiVorschlag", a.kiVorschlag(), n.kiVorschlag());
            feld(aenderungen, pfad + ".suchfilter", a.suchfilter(), n.suchfilter());
            feld(aenderungen, pfad + ".oeffentlichFilter", a.oeffentlichFilter(), n.oeffentlichFilter());
            feld(aenderungen, pfad + ".aktiv", a.aktiv(), n.aktiv());
            Map<String, KlassifikationWert> alteWerte = werteNachCode(a.werte());
            Map<String, KlassifikationWert> neueWerte = werteNachCode(n.werte());
            Set<String> wertCodes = new java.util.TreeSet<>(alteWerte.keySet());
            wertCodes.addAll(neueWerte.keySet());
            for (String wertCode : wertCodes) {
                KlassifikationWert alterWert = alteWerte.get(wertCode);
                KlassifikationWert neuerWert = neueWerte.get(wertCode);
                String wertpfad = pfad + ".werte." + wertCode;
                feld(aenderungen, wertpfad, vorhanden(alterWert), vorhanden(neuerWert));
                if (alterWert != null && neuerWert != null) {
                    feld(aenderungen, wertpfad + ".name", alterWert.name(), neuerWert.name());
                    feld(aenderungen, wertpfad + ".beschreibung", alterWert.beschreibung(),
                            neuerWert.beschreibung());
                    feld(aenderungen, wertpfad + ".reihenfolge", alterWert.reihenfolge(), neuerWert.reihenfolge());
                    feld(aenderungen, wertpfad + ".aktiv", alterWert.aktiv(), neuerWert.aktiv());
                }
            }
        }
    }

    private static Map<String, KlassifikationWert> werteNachCode(List<KlassifikationWert> werte) {
        Map<String, KlassifikationWert> ergebnis = new TreeMap<>();
        werte.forEach(wert -> ergebnis.put(wert.code(), wert));
        return ergebnis;
    }

    private static String vorhanden(Object wert) {
        return wert == null ? null : "<vorhanden>";
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
                      String kiHinweise, int reihenfolge, List<ArtKlassifikation> klassifikationen) {
        public Art(String code, String name, String beschreibung, List<String> gruppen,
                   List<String> pflichtfelder, String zusatzSchema, String vertraulichkeit,
                   Aufbewahrung aufbewahrung, boolean gesetzlichePflicht, boolean aktiv) {
            this(code, name, beschreibung, gruppen, pflichtfelder, zusatzSchema, vertraulichkeit, aufbewahrung,
                    gesetzlichePflicht, aktiv, false, List.of(), null, null, true, List.of(), null, 0, List.of());
        }

        public Art(String code, String name, String beschreibung, List<String> gruppen,
                   List<String> pflichtfelder, String zusatzSchema, String vertraulichkeit,
                   Aufbewahrung aufbewahrung, boolean gesetzlichePflicht, boolean aktiv,
                   boolean selbstAusgestellt, List<String> belegnummerMuster, String fremdbelegArt,
                   String ohneZahlungsdatenArt, boolean kiVorschlag, List<String> kiFelder,
                   String kiHinweise, int reihenfolge) {
            this(code, name, beschreibung, gruppen, pflichtfelder, zusatzSchema, vertraulichkeit, aufbewahrung,
                    gesetzlichePflicht, aktiv, selbstAusgestellt, belegnummerMuster, fremdbelegArt,
                    ohneZahlungsdatenArt, kiVorschlag, kiFelder, kiHinweise, reihenfolge, List.of());
        }

        public Art {
            gruppen = gruppen == null ? List.of() : List.copyOf(gruppen);
            pflichtfelder = pflichtfelder == null ? List.of() : List.copyOf(pflichtfelder);
            zusatzSchema = zusatzSchema == null || zusatzSchema.isBlank() ? OHNE_ZUSATZ : zusatzSchema;
            belegnummerMuster = belegnummerMuster == null ? List.of() : List.copyOf(belegnummerMuster);
            kiFelder = kiFelder == null ? List.of() : List.copyOf(kiFelder);
            klassifikationen = klassifikationen == null ? List.of() : List.copyOf(klassifikationen);
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

        /** UID und IBANs nie im Klartext ausgeben (Datensparsamkeit, Spec 05, Req 1.3). */
        @Override
        public String toString() {
            return "Kennungen[uid=" + (uid == null ? "keine" : "verdeckt") + ", ibans=" + ibans.size() + "]";
        }
    }

    /**
     * Ein geändertes Element: Pfad, alter und neuer Wert als Text ({@code null} = nicht vorhanden). Bei Kennungen
     * ({@link #ohneWerte()}) halten Protokoll und {@code toString()} nur den Pfad fest (Spec 05, Req 1.3).
     */
    public record Aenderung(String pfad, String alt, String neu) {
        private static final String KENNUNGEN = "einstellungen.kennungen.";

        public boolean ohneWerte() {
            return pfad != null && pfad.startsWith(KENNUNGEN);
        }

        @Override
        public String toString() {
            return ohneWerte() ? "Aenderung[pfad=" + pfad + ", geändert]"
                    : "Aenderung[pfad=" + pfad + ", alt=" + alt + ", neu=" + neu + "]";
        }
    }

    public record Befund(String pfad, String code, String meldung) {
    }
}
