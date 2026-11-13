package ch.urinet.archiv.integration.domain.mail;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Zugelassene Absender des Mail-Eingangs (Spec 02, Req 1.2, 1.4): Adressen ohne Anzeigenamen, verglichen ohne Rücksicht
 * auf Gross- und Kleinschreibung. Eine leere Liste lässt niemanden zu.
 */
public record Zulassungsliste(Set<String> adressen) {

    private static final Pattern ADRESSE = Pattern.compile("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,253}$");

    public Zulassungsliste {
        adressen = Set.copyOf(adressen);
    }

    /** Normalisiert die Adressen (getrimmt, klein); eine ungültige Adresse bricht mit ihrer Nummer ab, ohne Wert. */
    public static Zulassungsliste aus(Collection<String> adressen) {
        Set<String> sauber = new TreeSet<>();
        int nr = 0;
        for (String adresse : adressen) {
            nr++;
            String a = normalisieren(adresse);
            if (!ADRESSE.matcher(a).matches()) {
                throw new IllegalArgumentException("Ungültige Absenderadresse Nr. " + nr);
            }
            sauber.add(a);
        }
        return new Zulassungsliste(sauber);
    }

    public boolean zugelassen(Optional<String> absender) {
        return absender.map(Zulassungsliste::normalisieren).filter(adressen::contains).isPresent();
    }

    private static String normalisieren(String adresse) {
        return adresse == null ? "" : adresse.strip().toLowerCase(Locale.ROOT);
    }
}
