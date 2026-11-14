package ch.urinet.archiv.gemeinsam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prüft, welche Secrets ein Workflow nutzt (Req 16.7, 16.9). Erlaubt ist nur {@code secrets.<NAME>} mit einem Namen
 * aus der Liste; jedes andere Vorkommen des Wortes {@code secrets} ist ein Fund, auch {@code secrets.*},
 * {@code secrets: inherit}, {@code secrets[...]} und {@code toJSON(secrets)}. Ausnahme sind Pfade wie
 * {@code bundle/secrets/<name>} (direkt nach einem {@code /}, nicht gefolgt von {@code .} oder {@code [}), und nur in
 * Workflows mit Freigabe; in allen anderen ist jedes Vorkommen ein Fund. Ausdrücke
 * und Kontexte sind bei GitHub unabhängig von Gross- und Kleinschreibung, darum auch diese Prüfung; Fliesstext im
 * Workflow schreibt deshalb «Geheimnisse» statt «Secrets».
 */
final class WorkflowSecrets {

    /** Workflows, die Secrets brauchen dürfen, mit den erlaubten Namen: nur der Deploy (Aufgabe 1.9). */
    static final Map<String, Set<String>> ERLAUBT = Map.of("infomaniak-deploy.yml", Set.of(
            "INFOMANIAK_SSH_KEY", "ARCHIV_DB_POSTGRES", "ARCHIV_DB_OWNER", "ARCHIV_DB_APP", "KEYCLOAK_DB",
            "KEYCLOAK_ADMIN", "S3_APP_ZUGANG_ID", "S3_APP_ZUGANG_GEHEIMNIS",
            // Aufgabe 3.2: Token der Infomaniak AI Services als Secret-Datei (tech.md, Infomaniak AI Services)
            "INFOMANIAK_AI_TOKEN",
            // Aufgabe 5.2: privater Schlüssel des Tages-Manifests als Secret-Datei
            "ARCHIV_MANIFEST_KEY",
            // Spec 02, Aufgabe 6.1: Datenschlüssel für Zugangsdaten in der DB (Mail-Eingang), optional
            "ARCHIV_DATA_KEY",
            // Aufgabe 5.6 (Entscheid Fabian 14.11.2026): Passwort des Postfachs für Benachrichtigungen, optional
            "ARCHIV_SMTP_PASSWORT"));

    private static final Pattern WORT = Pattern.compile("(?i)\\bsecrets\\b");
    private static final Pattern NAME = Pattern.compile("\\G\\s*\\.\\s*([A-Za-z0-9_-]+)\\b(?!\\s*[.\\[(])");
    private static final Pattern WEITER = Pattern.compile("\\G\\s*[.\\[]");

    private WorkflowSecrets() {
    }

    /** Unerlaubte Vorkommen im Inhalt eines Workflows, je mit Zeilennummer; leer, wenn alles erlaubt ist. */
    static List<String> funde(String inhalt, Set<String> erlaubt) {
        List<String> funde = new ArrayList<>();
        var wort = WORT.matcher(inhalt);
        while (wort.find()) {
            int ende = wort.end();
            boolean pfad = !erlaubt.isEmpty() && wort.start() > 0 && inhalt.charAt(wort.start() - 1) == '/'
                    && !WEITER.matcher(inhalt).region(ende, inhalt.length()).lookingAt();
            if (pfad) {
                continue;
            }
            var name = NAME.matcher(inhalt).region(ende, inhalt.length());
            if (name.lookingAt() && erlaubt.contains(name.group(1).toUpperCase(Locale.ROOT))) {
                continue;
            }
            int zeile = (int) inhalt.substring(0, wort.start()).chars().filter(c -> c == '\n').count() + 1;
            String text = inhalt.lines().skip(zeile - 1L).findFirst().orElse("").strip();
            funde.add("Zeile " + zeile + ": " + text);
        }
        return funde;
    }
}
