package ch.urinet.archiv.integration.domain.webseite;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hauptversion der Webseiten-Schnittstelle: Pfad-Präfix {@code /public/v<n>} (Spec 03, design.md «Vertrag und
 * Versionierung», Req 11.4). Innerhalb einer Hauptversion ändert sich der Vertrag nur additiv.
 */
public record Hauptversion(int nummer) {

    public static final String PRAEFIX = "/public";
    private static final Pattern PFAD = Pattern.compile("^" + PRAEFIX + "/v([1-9][0-9]{0,3})(/.*)?$");

    public Hauptversion {
        if (nummer < 1) {
            throw new IllegalArgumentException("Hauptversion ab 1");
        }
    }

    /** Hauptversion eines Pfads unter {@code /public/v<n>}; leer für andere Pfade (z. B. {@code /public/x}). */
    public static Optional<Hauptversion> ausPfad(String pfad) {
        Matcher treffer = PFAD.matcher(pfad);
        return treffer.matches() ? Optional.of(new Hauptversion(Integer.parseInt(treffer.group(1)))) : Optional.empty();
    }

    /** Pfad-Präfix, z. B. {@code /public/v1}. */
    public String pfad() {
        return PRAEFIX + "/v" + nummer;
    }

    public Hauptversion nachfolgerin() {
        return new Hauptversion(nummer + 1);
    }
}
