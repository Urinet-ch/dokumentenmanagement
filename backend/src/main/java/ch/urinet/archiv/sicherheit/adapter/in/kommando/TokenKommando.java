package ch.urinet.archiv.sicherheit.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * {@code archiv token erstellen <mandant> <name> <scope>} (Req 2.5, 2.20) nach dem Muster von {@code mandant anlegen}:
 * Exit 0 = angelegt, 1 = fachlicher Fehler (ungültiger Scope, inaktiver Mandant), 2 = falscher Aufruf, 3 = technischer
 * Fehler. Das Token steht nur auf stdout, nie im Log und nie auf stderr.
 */
@ApplicationScoped
public class TokenKommando {

    public static final String AUFRUF = "Aufruf: archiv token erstellen <mandant> <name> <scope: generator|import>";

    private final ApiTokenVerwaltung verwaltung;

    public TokenKommando(ApiTokenVerwaltung verwaltung) {
        this.verwaltung = verwaltung;
    }

    public record Aufruf(String mandant, String name, String scope) {
    }

    /** Prüft die Argumente nach {@code token}, ohne die Anwendung zu brauchen; leer bei falschem Aufruf. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() != 4 || !argumente.getFirst().equals("erstellen")) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(argumente.get(1), argumente.get(2), argumente.get(3)));
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            ApiTokenVerwaltung.Erstellt erstellt = verwaltung.erstellen(aufruf.get().mandant(), aufruf.get().name(),
                    aufruf.get().scope());
            fehler.printf("API-Token %s für Mandant %s (Scope %s) angelegt; es wird nur jetzt angezeigt.%n",
                    erstellt.client().id(), erstellt.client().mandant(), erstellt.client().scope().code());
            aus.println(erstellt.token().text());
            return 0;
        } catch (FachFehler e) {
            fehler.println(e.code() + ": " + e.code().titel());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
