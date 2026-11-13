package ch.urinet.archiv.integration.adapter.in.kommando;

import ch.urinet.archiv.integration.application.mail.MailEinrichten;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code archiv mail einrichten <mandant> <host> <port> <benutzer> <absender>…} und {@code archiv mail abschalten
 * <mandant>} (Spec 02, Aufgabe 6.2) nach dem Muster von {@code token erstellen}: Das Passwort steht in der ersten Zeile
 * der Standardeingabe, nie in den Argumenten (sie stehen in der Prozessliste und der Shell-Historie), und gelangt nie ins
 * Log. Gelesen wird es vor dem Start der Anwendung ({@link MailPasswortEingabe}). Exit 0 = gespeichert, 1 = fachlicher Fehler (Mandant nicht aktiv, kein Datenschlüssel, ungültige Angabe),
 * 2 = falscher Aufruf, 3 = technischer Fehler.
 */
@ApplicationScoped
public class MailKommando {

    public static final String AUFRUF = "Aufruf: archiv mail einrichten <mandant> <host> <port> <benutzer> "
            + "<absender> [<absender>…] (Passwort als erste Zeile der Standardeingabe) | archiv mail abschalten "
            + "<mandant>";

    private final MailEinrichten einrichten;

    public MailKommando(MailEinrichten einrichten) {
        this.einrichten = einrichten;
    }

    public record Aufruf(String aktion, String mandant, String host, int port, String benutzer,
                         List<String> absender) {
    }

    /** Prüft die Argumente nach {@code mail}, ohne die Anwendung zu brauchen; leer bei falschem Aufruf. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() == 2 && argumente.getFirst().equals("abschalten")) {
            return Optional.of(new Aufruf("abschalten", argumente.get(1), null, 0, null, List.of()));
        }
        if (argumente.size() < 6 || !argumente.getFirst().equals("einrichten")) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(argumente.get(3));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf("einrichten", argumente.get(1), argumente.get(2), port, argumente.get(4),
                List.copyOf(argumente.subList(5, argumente.size()))));
    }

    /**
     * @param passwort liefert die erste Zeile der Standardeingabe, die {@code ArchivMain.main} vor dem Start gelesen hat
     *                 ({@link MailPasswortEingabe}); nur für {@code einrichten} abgefragt, danach überschrieben
     */
    public int ausfuehren(List<String> argumente, Supplier<char[]> passwort, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        Aufruf a = aufruf.get();
        try {
            if (a.aktion().equals("abschalten")) {
                boolean gab = einrichten.abschalten(a.mandant());
                fehler.println(gab ? "Mail-Eingang für Mandant " + a.mandant() + " abgeschaltet."
                        : "Für Mandant " + a.mandant() + " ist kein Postfach eingerichtet.");
                return 0;
            }
            char[] zeichen = passwort.get();
            try {
                einrichten.einrichten(a.mandant(), a.host(), a.port(), a.benutzer(), new String(zeichen),
                        a.absender());
            } finally {
                Arrays.fill(zeichen, '\0');
            }
            fehler.printf("Postfach für Mandant %s eingerichtet (Port %d, %d zugelassene Absender); der Worker ruft es "
                    + "im nächsten Takt ab.%n", a.mandant(), a.port(), a.absender().size());
            return 0;
        } catch (MailEinrichten.Fehler e) {
            fehler.println(e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
