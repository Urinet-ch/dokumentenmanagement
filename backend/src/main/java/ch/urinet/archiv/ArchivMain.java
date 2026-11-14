package ch.urinet.archiv;

import ch.urinet.archiv.integration.adapter.in.kommando.MailKommando;
import ch.urinet.archiv.integration.adapter.in.kommando.MailPasswortEingabe;
import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import ch.urinet.archiv.protokoll.adapter.in.kommando.ManifestKommando;
import ch.urinet.archiv.sicherheit.adapter.in.kommando.TokenKommando;
import ch.urinet.archiv.verarbeitung.adapter.in.kommando.EmbeddingKommando;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import liquibase.Scope;
import liquibase.ui.LoggerUIService;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Ohne Argumente läuft der Dienst; mit Argumenten ein Admin-Kommando wie {@code archiv mandant anlegen urinet} oder
 * {@code archiv token erstellen urinet Rechnungsgenerator generator} oder
 * {@code archiv embedding nachberechnen urinet}.
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

    /**
     * {@code archiv manifest pruefen … --ohne-datenbank} (Req 15.12): Das Prüfwerkzeug braucht nur Ablage und öffentlichen
     * Schlüssel. Ohne Datasource entfallen Migration (Liquibase) und Hibernate, die Pflicht zur Adresse der Datenbank
     * ({@code DatenbankAdressePruefung}) gilt dann nicht.
     */
    public static final Map<String, String> OHNE_DATENBANK = Map.of(
            "quarkus.datasource.active", "false",
            "quarkus.hibernate-orm.active", "false",
            "quarkus.liquibase.migrate-at-start", "false",
            "archiv.db.url-pflicht", "false");

    static final int FACHLICHER_FEHLER = 1;
    static final int FALSCHER_AUFRUF = 2;
    static final int TECHNISCHER_FEHLER = 3;

    /** stdout gehört im Kommando-Modus nur dem Ergebnis des Kommandos. */
    private static PrintStream ergebnis = System.out;

    @Inject
    MandantKommando mandant;

    @Inject
    TokenKommando token;

    @Inject
    ManifestKommando manifest;

    @Inject
    MailKommando mail;

    @Inject
    EmbeddingKommando embedding;

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
        if (argumente[0].equals("mail") && argumente[1].equals("einrichten")) {
            int code = mailPasswortVorabLesen(System.in);
            if (code != 0) {
                beenden.accept(code, null);
                return;
            }
        }
        KOMMANDO.forEach(System::setProperty);
        if (argumente[0].equals("manifest") && List.of(argumente).contains(ManifestKommando.OHNE_DATENBANK)) {
            OHNE_DATENBANK.forEach(System::setProperty);
        }
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
            case "manifest" -> ManifestKommando.lesen(rest).isPresent() ? Optional.empty()
                    : Optional.of(ManifestKommando.AUFRUF);
            case "mail" -> MailKommando.lesen(rest).isPresent() ? Optional.empty()
                    : Optional.of(MailKommando.AUFRUF);
            case "embedding" -> EmbeddingKommando.lesen(rest).isPresent() ? Optional.empty()
                    : Optional.of(EmbeddingKommando.AUFRUF);
            default -> Optional.of("Unbekanntes Kommando. " + MandantKommando.AUFRUF + System.lineSeparator()
                    + TokenKommando.AUFRUF + System.lineSeparator() + ManifestKommando.AUFRUF
                    + System.lineSeparator() + MailKommando.AUFRUF + System.lineSeparator() + EmbeddingKommando.AUFRUF);
        };
    }

    /**
     * {@code mail einrichten}: das Passwort (erste Zeile der Standardeingabe) lesen, bevor Quarkus startet. Im Befund
     * von Stufe A (13.11.2026) kam die Eingabe im Wegwerf-Container nicht mehr an, wenn sie erst nach dem Start (rund
     * 50 s) gelesen wurde (Spec 02, design.md «Umsetzung (Aufgabe 6.1)»). Ohne oder mit zu langem Passwort endet das
     * Kommando sofort mit 1, ist die Standardeingabe nicht lesbar mit 3 (nur die Klasse des Fehlers, nie seine Meldung).
     */
    static int mailPasswortVorabLesen(InputStream ein) {
        try {
            Optional<String> fehler = MailPasswortEingabe.vorabLesen(ein);
            fehler.ifPresent(System.err::println);
            return fehler.isPresent() ? FACHLICHER_FEHLER : 0;
        } catch (IOException e) {
            System.err.println("Technischer Fehler: Standardeingabe nicht lesbar (" + e.getClass().getSimpleName() + ")");
            return TECHNISCHER_FEHLER;
        }
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
        return switch (liste.getFirst()) {
            case "token" -> token.ausfuehren(rest, ergebnis, System.err);
            case "manifest" -> manifest.ausfuehren(rest, ergebnis, System.err);
            case "mail" -> mail.ausfuehren(rest, MailPasswortEingabe::nehmen, System.err);
            case "embedding" -> embedding.ausfuehren(rest, ergebnis, System.err);
            default -> mandant.ausfuehren(rest, ergebnis, System.err);
        };
    }
}
