package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** SQL-Prädikat für die Dokumentensicht gemäss {@link Sichtregel}. */
public final class SichtregelSql {

    public record Ausdruck(String bedingung, Map<String, Object> parameter) {
        public Ausdruck {
            parameter = Map.copyOf(parameter);
        }
    }

    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    private SichtregelSql() {
    }

    /**
     * Sicht eines Benutzers der App. Für eine Regel der Webseiten-Schnittstelle bricht die Methode ab, weil ihr fester
     * Filter hier fehlt: lieber ein Fehler als eine zu weite Sicht.
     */
    public static Ausdruck dokument(Sichtregel regel, String alias) {
        if (regel.webseite() != null) {
            throw new IllegalStateException("Sichtregel der Webseite nur mit dem festen Filter der Webseite");
        }
        return ohneFestenFilter(regel, alias);
    }

    /**
     * Sicht nach Vertraulichkeit und Bestätigung ohne den festen Filter der Webseiten-Schnittstelle; wer eine Regel der
     * Webseite übergibt, muss diesen Filter selbst ergänzen.
     */
    public static Ausdruck ohneFestenFilter(Sichtregel regel, String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Ungültiger Alias");
        }
        Map<String, Object> parameter = new HashMap<>();
        if (regel.uneingeschraenkt()) {
            return new Ausdruck("1 = 1", parameter);
        }

        String bestaetigt = alias + ".bestaetigt";
        String erstelltVon = alias + ".erstellt_von";
        String vertraulichkeit = alias + ".vertraulichkeit";
        String personBenutzer = alias + ".person_benutzer";

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
