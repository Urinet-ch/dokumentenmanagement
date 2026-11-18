package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.konfiguration.domain.Regel;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class RegelPruefung {

    private static final Comparator<String> CODEPOINTS = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private final KonfigurationVerwaltung konfiguration;
    private final RegelDatenPort daten;
    private final ZusatzSchemaPruefer zusatzSchema;

    public RegelPruefung(KonfigurationVerwaltung konfiguration, RegelDatenPort daten,
                         ZusatzSchemaPruefer zusatzSchema) {
        this.konfiguration = konfiguration;
        this.daten = daten;
        this.zusatzSchema = zusatzSchema;
    }

    public Map<String, Object> zusatzWerte(String json) {
        return zusatzSchema.werteLesen(json);
    }

    /** Regeln der aktuellen Konfiguration, etwa einmal je Seite des Eingangskorbs für {@link #warnungen}. */
    public List<Regel> regeln() {
        return konfiguration.aktuell().regeln();
    }

    /**
     * Schreibpfad (Erfassen, Ändern, Bestätigen): verletzte FEHLER-Regeln brechen ab; EINDEUTIG nimmt eine Sperre bis
     * zum Commit, damit zwei gleichzeitige Speicherungen derselben Werte nicht beide durchgehen.
     */
    public List<Warnung> pruefen(String art, Map<String, Object> werte, Map<String, Object> vorher,
                                 UUID dokumentId) {
        return pruefen(regeln(), art, werte, vorher, dokumentId, true);
    }

    /**
     * Lesepfad (Detail, Eingangskorb): nur Warnungen, ohne Sperre; jede verletzte Regel erscheint als Warnung, weil
     * nichts geändert wird (Req 5.3).
     */
    public List<Warnung> warnungen(List<Regel> regeln, String art, Map<String, Object> werte, UUID dokumentId) {
        return pruefen(regeln, art, werte, werte, dokumentId, false);
    }

    private List<Warnung> pruefen(List<Regel> regeln, String art, Map<String, Object> werte,
                                  Map<String, Object> vorher, UUID dokumentId, boolean sperren) {
        List<RegelVerletzt.Befund> fehler = new ArrayList<>();
        List<Warnung> warnungen = new ArrayList<>();
        for (Regel regel : regeln) {
            if (!regel.aktiv() || regel.art() != null && !regel.art().equals(art)) {
                continue;
            }
            Regel.Parameter parameter = regel.typisierteParameter();
            if (!(parameter instanceof Regel.PflichtWenn || parameter instanceof Regel.Eindeutig)) {
                continue;
            }
            if (!verletzt(regel, parameter, werte, dokumentId, sperren)) {
                continue;
            }
            // Ein Artwechsel macht die Regel erst anwendbar und gilt darum als Änderung aller Regelfelder (Req 5.3)
            boolean geaendert = vorher == null || !Objects.equals(vorher.get("art"), werte.get("art"))
                    || parameter.felder().stream().anyMatch(feld -> !Objects.equals(
                    normalisiert(feld, vorher.get(feld)), normalisiert(feld, werte.get(feld))));
            String schwere = geaendert ? regel.schwere() : "WARNUNG";
            if ("FEHLER".equals(schwere)) {
                fehler.add(new RegelVerletzt.Befund(regel.code(), regel.meldung()));
            } else {
                warnungen.add(new Warnung(regel.code(), regel.meldung()));
            }
        }
        if (!fehler.isEmpty()) {
            throw new RegelVerletzt(fehler);
        }
        return List.copyOf(warnungen);
    }

    private boolean verletzt(Regel regel, Regel.Parameter parameter, Map<String, Object> werte, UUID dokumentId,
                             boolean sperren) {
        if (parameter instanceof Regel.PflichtWenn pflicht) {
            Object bedingung = werte.get(pflicht.wennFeld());
            boolean gilt = bedingung instanceof List<?> liste
                    ? liste.stream().anyMatch(wert -> gleich(wert, pflicht.gleich()))
                    : gleich(bedingung, pflicht.gleich());
            return gilt && pflicht.dann().stream().anyMatch(feld -> !vorhanden(werte.get(feld)));
        }
        Regel.Eindeutig eindeutig = (Regel.Eindeutig) parameter;
        List<Object> werteDerFelder = eindeutig.felder().stream().map(feld -> normalisiert(feld, werte.get(feld)))
                .toList();
        if (werteDerFelder.stream().anyMatch(wert -> !vorhanden(wert))) {
            return false;
        }
        return daten.dubletteVorhanden(regel.art(), regel.art() == null || eindeutig.ueberAlleArten(),
                eindeutig.felder(), werteDerFelder, dokumentId, sperren);
    }

    // Zahlen nach Wert vergleichen: 1, 1.0 und 1.00 aus JSON oder BigDecimal-Spalten sind gleich
    static boolean gleich(Object wert, Object erwartet) {
        if (wert instanceof Number a && erwartet instanceof Number b) {
            try {
                return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return Objects.equals(wert, erwartet);
    }

    // Mehrfachwerte (Tags, Klassifikationen) gelten als Menge: sortiert und ohne Duplikate, wie in der Datenbank
    static Object normalisiert(String feld, Object wert) {
        if (("tags".equals(feld) || feld.startsWith("klassifikation.")) && wert instanceof List<?> liste) {
            // nach Codepoints sortiert wie «collate "C"» der Abfrage (UTF-16-Reihenfolge weicht ausserhalb der BMP ab)
            return liste.stream().filter(Objects::nonNull).map(String::valueOf).distinct()
                    .sorted(CODEPOINTS).toList();
        }
        return wert;
    }

    private static boolean vorhanden(Object wert) {
        if (wert == null) {
            return false;
        }
        if (wert instanceof String text) {
            return !text.isBlank();
        }
        if (wert instanceof List<?> liste) {
            return !liste.isEmpty();
        }
        if (wert instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    public record Warnung(String code, String meldung) {
    }
}
