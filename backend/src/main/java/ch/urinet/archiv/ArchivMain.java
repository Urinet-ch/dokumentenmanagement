package ch.urinet.archiv;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.util.List;

/** Ohne Argumente läuft der Dienst; mit Argumenten ein Admin-Kommando wie {@code archiv mandant anlegen urinet}. */
@QuarkusMain
public class ArchivMain implements QuarkusApplication {

    @Inject
    MandantKommando mandant;

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
