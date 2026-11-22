package ch.urinet.archiv.integration.domain.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Filter der Webseiten-Liste (Spec 03, Req 5.1): Reihe (Code der Dokumentart), Jahr, Ausgabe und öffentlich
 * filterbare Klassifikationen. Die Werte sind Auswahl, kein Schutz; der feste Filter gilt immer zusätzlich. Jede
 * ungültige Angabe ist 400 {@code ANFRAGE_UNGUELTIG}, ohne den Wert zu nennen.
 */
public record ListenFilter(String reihe, String jahr, String ausgabe, Map<String, List<String>> klassifikationen) {

    public static final ListenFilter OHNE = new ListenFilter(null, null, null);
    public static final int AUSGABE_MAX = 50;
    private static final Pattern REIHE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern JAHR = Pattern.compile("^[1-9][0-9]{3}$");
    private static final Pattern KLASSIFIKATION = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    public ListenFilter(String reihe, String jahr, String ausgabe) {
        this(reihe, jahr, ausgabe, Map.of());
    }

    public ListenFilter {
        reihe = leer(reihe);
        jahr = leer(jahr);
        ausgabe = leer(ausgabe);
        pruefen(reihe == null || REIHE.matcher(reihe).matches());
        pruefen(jahr == null || JAHR.matcher(jahr).matches());
        pruefen(ausgabe == null || (ausgabe.length() <= AUSGABE_MAX
                && ausgabe.chars().noneMatch(Character::isISOControl)));
        Map<String, List<String>> filter = new TreeMap<>();
        pruefen(klassifikationen == null || klassifikationen.size() <= 50);
        if (klassifikationen != null) {
            klassifikationen.forEach((code, werte) -> {
                pruefen(code != null && KLASSIFIKATION.matcher(code).matches());
                pruefen(werte != null && !werte.isEmpty() && werte.size() <= 500
                        && werte.stream().allMatch(wert -> wert != null && KLASSIFIKATION.matcher(wert).matches()));
                filter.put(code, werte.stream().distinct().toList());
            });
        }
        klassifikationen = Collections.unmodifiableMap(filter);
    }

    private static String leer(String wert) {
        return wert == null || wert.isBlank() ? null : wert.strip();
    }

    private static void pruefen(boolean bedingung) {
        if (!bedingung) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
