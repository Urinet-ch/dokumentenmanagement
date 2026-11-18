package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prädikat für die Dokumentensicht gemäss {@link Sichtregel} (Req 2.3, 2.21). Einzige Quelle für SQL (Suche,
 * Wissensbasis) und HQL (Dokumentlisten): Die Sprachen unterscheiden sich nur in den Spaltennamen.
 */
public final class SichtregelSql {

    /** Spaltennamen je Sprache: HQL-Attribute der Entität oder SQL-Spalten mit Alias; feste Bezeichner. */
    public record Spalten(String bestaetigt, String erstelltVon, String vertraulichkeit, String personBenutzer) {
        public Spalten {
            for (String spalte : List.of(bestaetigt, erstelltVon, vertraulichkeit, personBenutzer)) {
                if (!SPALTE.matcher(spalte).matches()) {
                    throw new IllegalArgumentException("Ungültige Spalte");
                }
            }
        }

        /** SQL-Spalten der Tabelle {@code dokument} unter dem Alias {@code alias}. */
        public static Spalten sql(String alias) {
            if (!ALIAS.matcher(alias).matches()) {
                throw new IllegalArgumentException("Ungültiger Alias");
            }
            return new Spalten(alias + ".bestaetigt", alias + ".erstellt_von", alias + ".vertraulichkeit",
                    alias + ".person_benutzer");
        }
    }

    public record Ausdruck(String bedingung, Map<String, Object> parameter) {
        public Ausdruck {
            parameter = Map.copyOf(parameter);
        }
    }

    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");
    private static final Pattern SPALTE = Pattern.compile("^([a-z][a-z0-9_]{0,15}\\.)?[a-zA-Z][a-zA-Z0-9_]{0,30}$");

    private SichtregelSql() {
    }

    /** SQL-Prädikat auf {@code dokument} unter dem Alias {@code alias}. */
    public static Ausdruck dokument(Sichtregel regel, String alias) {
        return praedikat(regel, Spalten.sql(alias));
    }

    /** Prädikat über die angegebenen Spalten; der feste Filter der Webseite gehört nicht dazu. */
    public static Ausdruck praedikat(Sichtregel regel, Spalten spalten) {
        Map<String, Object> parameter = new HashMap<>();
        if (regel.uneingeschraenkt()) {
            return new Ausdruck("1 = 1", parameter);
        }

        String bestaetigt = spalten.bestaetigt();
        String erstelltVon = spalten.erstelltVon();
        String vertraulichkeit = spalten.vertraulichkeit();
        String personBenutzer = spalten.personBenutzer();

        // Unbestätigte Art wirkt als PERSONAL (Req 2.21, Fussnote ², design.md «Sicherheit»)
        String unbestaetigt = switch (regel.unbestaetigt()) {
            case ALLE -> bestaetigt + " is null";
            case ERFASSTE -> {
                parameter.put("benutzer", regel.benutzer());
                yield "(" + bestaetigt + " is null and " + erstelltVon + " = :benutzer)";
            }
            case KEINE -> "1 = 0";
        };
        List<String> stufen = new ArrayList<>();
        if (regel.normal()) {
            stufen.add(vertraulichkeit + " = 'NORMAL'");
        }
        if (regel.intern()) {
            stufen.add(vertraulichkeit + " = 'INTERN'");
        }
        switch (regel.personal()) {
            case ALLE -> stufen.add(vertraulichkeit + " = 'PERSONAL'");
            case NUR_EIGENE -> {
                stufen.add("(" + vertraulichkeit + " = 'PERSONAL' and " + personBenutzer + " = :benutzer)");
                parameter.put("benutzer", regel.benutzer());
            }
            case KEINE -> {
                // Keine bestätigten PERSONAL-Dokumente.
            }
        }
        String bestaetigteStufen = stufen.isEmpty() ? "1 = 0"
                : "(" + bestaetigt + " is not null and (" + String.join(" or ", stufen) + "))";
        return new Ausdruck("(" + unbestaetigt + " or " + bestaetigteStufen + ")", parameter);
    }
}
