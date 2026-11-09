package ch.urinet.archiv.spike.golden;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.util.List;

/**
 * Aufgabe 0.5. Standard: nur Abstimmungsteil (30). --gate: nur Prüfteil (20, startet Fabian). --realitaet: nur
 * bestätigte echte Belege (Req 0.10). --k1: Variante Bild statt OCR-Text (nur Abstimmungsteil). --limit N für Probeläufe.
 */
@QuarkusMain
public class GoldenLauf implements QuarkusApplication {

    @Inject
    GoldenLaufDienst dienst;

    @Override
    @ActivateRequestContext
    public int run(String... args) throws Exception {
        List<String> argumente = List.of(args);
        Modus modus = Modus.aus(argumente);
        int i = argumente.indexOf("--limit");
        int limit = i >= 0 && i + 1 < argumente.size() ? Integer.parseInt(argumente.get(i + 1)) : 0;
        GoldenLaufDienst.Ausgang a = dienst.lauf(modus, limit, argumente.contains("--k1"));
        System.out.println();
        System.out.println(Files.readString(a.ordner().resolve("kennzahlen.md")));
        System.out.println("Bericht: " + a.ordner());
        return 0;
    }
}
