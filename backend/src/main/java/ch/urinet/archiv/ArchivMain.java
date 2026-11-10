package ch.urinet.archiv;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import ch.urinet.archiv.sicherheit.adapter.in.kommando.TokenKommando;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import liquibase.Scope;
import liquibase.ui.LoggerUIService;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Ohne Argumente läuft der Dienst; mit Argumenten ein Admin-Kommando wie {@code archiv mandant anlegen urinet} oder
 * {@code archiv token erstellen urinet Rechnungsgenerator generator}.
 * Exit-Codes des Kommandos: 0 ok, 1 fachlicher Fehler, 2 falscher Aufruf (geprüft, bevor Quarkus startet),
 * 3 technischer Fehler, auch wenn schon der Start der Anwendung scheitert (z. B. falscher Zugang für {@code archiv_owner}).
 */
@QuarkusMain
public class ArchivMain implements QuarkusApplication {

    /**
     * Kommandos laufen neben dem Dienst im selben Container: kein HTTP-Server, kein OIDC, keine Rollen-Funktion. Logs
     * gehen auf stderr, damit {@code archiv token erstellen … > token} nur das Token in die Datei schreibt; die
     * Zusammenfassung jeder Liquibase-Migration (public und Mandanten) nur ins Log, nicht in den Ausgabestrom.
     */
    public static final Map<String, String> KOMMANDO = Map.of(
            "archiv.rolle", "kommando",
            "quarkus.http.host-enabled", "false",
            "quarkus.oidc.tenant-enabled", "false",
            "quarkus.log.console.stderr", "true",
            "liquibase.command.showSummaryOutput", "log");

    static final int FALSCHER_AUFRUF = 2;
    static final int TECHNISCHER_FEHLER = 3;

    /** stdout gehört im Kommando-Modus nur dem Ergebnis des Kommandos. */
    private static PrintStream ergebnis = System.out;

    @Inject
    MandantKommando mandant;

    @Inject
    TokenKommando token;

    public static void main(String... argumente) {
        if (argumente.length == 0) {
            // auch im Dienst geht die Liquibase-Ausgabe der public-Migration ins Log statt roh auf stdout
            liquibaseInsLog();
            Quarkus.run(ArchivMain.class, argumente);
            return;
        }
        // in prod System.exit, in @QuarkusMainTest der Ergebnis-Handler des Tests
        BiConsumer<Integer, Throwable> beenden = ApplicationLifecycleManager.getDefaultExitCodeHandler();
        Optional<String> fehler = aufrufPruefen(argumente);
        if (fehler.isPresent()) {
            System.err.println(fehler.get());
            beenden.accept(FALSCHER_AUFRUF, null);
            return;
        }
        KOMMANDO.forEach(System::setProperty);
        String liquibaseScope = liquibaseInsLog();
        // Zweite Sicherung: Was eine Bibliothek trotzdem auf System.out schreibt, geht auf stderr, nicht ins Ergebnis.
        ergebnis = System.out;
        System.setOut(System.err);
        Quarkus.run(ArchivMain.class, (code, ursache) -> {
            System.setOut(ergebnis);
            liquibaseScopeVerlassen(liquibaseScope);
            if (ursache != null) {
                // mit eigenem Handler meldet Quarkus den Startfehler nicht selbst; nur die Meldung, kein Stacktrace
                System.err.println("Start fehlgeschlagen: " + grundursache(ursache).getMessage());
            }
            beenden.accept(ursache != null ? TECHNISCHER_FEHLER : code, ursache);
        }, argumente);
    }

    /**
     * Liquibase 4.33 schreibt als Bibliothek über seinen Standard-{@code ConsoleUIService} fest auf System.out
     * ({@code liquibase.uiService} gilt nur in seiner CLI). Der Wurzel-Scope gilt für alle Liquibase-Aufrufe des
     * Prozesses, auch für die public-Migration beim Start; mit {@link LoggerUIService} landet die Ausgabe im Log wie bei
     * der Mandanten-Migration ({@code LiquibaseMandantDatenbank}). Gilt für Kommando und Dienst; der Dienst verlässt
     * den Scope nicht, er gilt bis zum Ende des Prozesses.
     */
    static String liquibaseInsLog() {
        try {
            return Scope.enter(Map.of(Scope.Attr.ui.name(), new LoggerUIService()));
        } catch (Exception e) {
            throw new IllegalStateException("Liquibase-Ausgabe nicht ins Log umgeleitet", e);
        }
    }

    private static void liquibaseScopeVerlassen(String id) {
        try {
            Scope.exit(id);
        } catch (Exception e) {
            // nur nach dem Kommando: der Prozess endet ohnehin
            System.err.println("Liquibase-Scope nicht verlassen: " + e.getClass().getName());
        }
    }

    /** Leer, wenn der Aufruf gültig ist, sonst die Aufrufhilfe. */
    static Optional<String> aufrufPruefen(String... argumente) {
        List<String> liste = List.of(argumente);
        List<String> rest = liste.subList(1, liste.size());
        return switch (liste.getFirst()) {
            case "mandant" -> MandantKommando.lesen(rest).isPresent() ? Optional.empty()
                    : Optional.of(MandantKommando.AUFRUF);
            case "token" -> TokenKommando.lesen(rest).isPresent() ? Optional.empty()
                    : Optional.of(TokenKommando.AUFRUF);
            default -> Optional.of("Unbekanntes Kommando. " + MandantKommando.AUFRUF + System.lineSeparator()
                    + TokenKommando.AUFRUF);
        };
    }

    private static Throwable grundursache(Throwable fehler) {
        Throwable ursache = fehler;
        while (ursache.getCause() != null && ursache.getCause() != ursache) {
            ursache = ursache.getCause();
        }
        return ursache;
    }

    @Override
    public int run(String... argumente) {
        if (argumente.length == 0) {
            Quarkus.waitForExit();
            return 0;
        }
        List<String> liste = List.of(argumente);
        List<String> rest = liste.subList(1, liste.size());
        return liste.getFirst().equals("token") ? token.ausfuehren(rest, ergebnis, System.err)
                : mandant.ausfuehren(rest, ergebnis, System.err);
    }
}
