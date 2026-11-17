package ch.urinet.archiv.konfiguration.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Unveränderliches Abbild der fachlichen Konfiguration eines Mandanten. */
public record Konfiguration(int version, Einstellungen einstellungen, List<Art> arten) {

    public Konfiguration {
        arten = List.copyOf(arten);
    }

    /** Liefert alle Prüfungen, die ohne die späteren Konfigurationsattribute möglich sind. */
    public List<Befund> pruefen() {
        List<Befund> befunde = new ArrayList<>();
        if (version < 1) {
            befunde.add(new Befund("version", "VERSION_UNGUELTIG", "Die Konfigurationsversion muss positiv sein."));
        }
        Set<String> codes = new HashSet<>();
        for (Art art : arten) {
            if (!codes.add(art.code())) {
                befunde.add(new Befund("arten." + art.code(), "DOKUMENTART_DOPPELT",
                        "Der Dokumentart-Code kommt mehrfach vor."));
            }
        }
        return List.copyOf(befunde);
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
            feld(aenderungen, pfad + "gesetzlichePflicht", alt.gesetzlichePflicht(), aktuell.gesetzlichePflicht());
            feld(aenderungen, pfad + "aktiv", alt.aktiv(), aktuell.aktiv());
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
    }

    public record Art(String code, String name, String beschreibung, List<String> gruppen,
                      List<String> pflichtfelder, String zusatzSchema, String vertraulichkeit,
                      Aufbewahrung aufbewahrung, boolean gesetzlichePflicht, boolean aktiv) {
        public Art {
            gruppen = List.copyOf(gruppen);
            pflichtfelder = List.copyOf(pflichtfelder);
        }
    }

    public record Aufbewahrung(Integer jahre, String ab) {
    }

    public record Kennungen(String uid, List<String> ibans) {
        public Kennungen {
            ibans = List.copyOf(ibans);
        }
    }

    public record Aenderung(String pfad, String alt, String neu) {
    }

    public record Befund(String pfad, String code, String meldung) {
    }
}
