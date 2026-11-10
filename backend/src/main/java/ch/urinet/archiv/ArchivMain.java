package ch.urinet.archiv;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Ohne Argumente läuft der Dienst; mit Argumenten ein Admin-Kommando wie {@code archiv mandant anlegen urinet}. */
@QuarkusMain
public class ArchivMain implements QuarkusApplication {

    /** Kommandos laufen neben dem Dienst im selben Container: kein HTTP-Server, kein OIDC, keine Rollen-Funktion. */
    public static final Map<String, String> KOMMANDO = Map.of(
            "archiv.rolle", "kommando",
            "quarkus.http.host-enabled", "false",
            "quarkus.oidc.tenant-enabled", "false");

    @Inject
    MandantKommando mandant;

    public static void main(String... argumente) {
        if (argumente.length > 0) {
            KOMMANDO.forEach(System::setProperty);
        }
        Quarkus.run(ArchivMain.class, argumente);
    }

    @Override
    public int run(String... argumente) {
        if (argumente.length == 0) {
            Quarkus.waitForExit();
            return 0;
        }
        List<String> liste = List.of(argumente);
        if (liste.getFirst().equals("mandant")) {
            return mandant.ausfuehren(liste.subList(1, liste.size()), System.out, System.err);
        }
        System.err.println("Unbekanntes Kommando. " + MandantKommando.AUFRUF);
        return 2;
    }
}
