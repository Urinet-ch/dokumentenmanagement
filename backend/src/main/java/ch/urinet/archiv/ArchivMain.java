package ch.urinet.archiv;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Ohne Argumente läuft der Dienst; mit Argumenten ein Admin-Kommando wie {@code archiv mandant anlegen urinet}.
 * Exit-Codes des Kommandos: 0 ok, 1 fachlicher Fehler, 2 falscher Aufruf (geprüft, bevor Quarkus startet),
 * 3 technischer Fehler, auch wenn schon der Start der Anwendung scheitert (z. B. falscher Zugang für {@code archiv_owner}).
 */
@QuarkusMain
public class ArchivMain implements QuarkusApplication {

    /** Kommandos laufen neben dem Dienst im selben Container: kein HTTP-Server, kein OIDC, keine Rollen-Funktion. */
    public static final Map<String, String> KOMMANDO = Map.of(
            "archiv.rolle", "kommando",
            "quarkus.http.host-enabled", "false",
            "quarkus.oidc.tenant-enabled", "false");

    static final int FALSCHER_AUFRUF = 2;
    static final int TECHNISCHER_FEHLER = 3;

    @Inject
    MandantKommando mandant;

    public static void main(String... argumente) {
        if (argumente.length == 0) {
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
        Quarkus.run(ArchivMain.class, (code, ursache) -> {
            if (ursache != null) {
                // mit eigenem Handler meldet Quarkus den Startfehler nicht selbst; nur die Meldung, kein Stacktrace
                System.err.println("Start fehlgeschlagen: " + grundursache(ursache).getMessage());
            }
            beenden.accept(ursache != null ? TECHNISCHER_FEHLER : code, ursache);
        }, argumente);
    }

    /** Leer, wenn der Aufruf gültig ist, sonst die Aufrufhilfe. */
    static Optional<String> aufrufPruefen(String... argumente) {
        List<String> liste = List.of(argumente);
        if (!liste.getFirst().equals("mandant")) {
            return Optional.of("Unbekanntes Kommando. " + MandantKommando.AUFRUF);
        }
        return MandantKommando.lesen(liste.subList(1, liste.size())).isPresent()
                ? Optional.empty() : Optional.of(MandantKommando.AUFRUF);
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
        return mandant.ausfuehren(liste.subList(1, liste.size()), System.out, System.err);
    }
}
