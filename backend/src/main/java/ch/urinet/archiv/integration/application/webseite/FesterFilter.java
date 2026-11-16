package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;

import java.util.regex.Pattern;

/**
 * Fester Sicherheitsfilter jeder lesenden Abfrage der Webseiten-Schnittstelle und des öffentlichen Chatbots (Spec 03,
 * design.md «Fester Filter in allen Abfragen», Req 3.4, 3.6, 3.7, 5.3): nur {@code ARCHIVIERT}, Vertraulichkeit
 * {@code NORMAL}, {@code veroeffentlicht} als JSON-{@code true} (Vergleich als {@code jsonb}: der Text {@code "true"} zählt nicht) und {@code zugriff} in den Stufen bis zur wirksamen
 * Stufe. Abfragen auf {@code dokument} hängen {@link #bedingung(String)} mit {@code and} an und binden
 * {@link #PARAMETER} mit {@link #stufen(Lesezugriff)} (ein {@code String[]}, nie ein Wert aus der Anfrage). Die
 * Bedingung gilt zusätzlich zu allen anderen Filtern, nie statt ihrer; sie kennt keine Rolle und keine Sichtregel der
 * App.
 */
public final class FesterFilter {

    /** Name des benannten Parameters für die Stufen. */
    public static final String PARAMETER = "stufen";

    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    private FesterFilter() {
    }

    /** SQL-Bedingung auf {@code dokument} unter dem Alias {@code alias} (fester Bezeichner, nie Benutzereingabe). */
    public static String bedingung(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Ungültiger Alias");
        }
        return "(" + alias + ".status = 'ARCHIVIERT' and " + alias + ".vertraulichkeit = 'NORMAL' and "
                + alias + ".zusatz -> 'veroeffentlicht' = 'true'::jsonb and "
                + alias + ".zusatz ->> 'zugriff' = any(:" + PARAMETER + "))";
    }

    /** Wert für {@link #PARAMETER}. */
    public static String[] stufen(Lesezugriff zugriff) {
        return zugriff.stufen().toArray(String[]::new);
    }
}
