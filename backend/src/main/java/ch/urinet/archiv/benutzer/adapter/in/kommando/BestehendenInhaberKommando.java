package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.benutzer.application.BestehendenInhaberDokumentieren;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class BestehendenInhaberKommando {
    public static final String AUFRUF = "Aufruf: archiv benutzer inhaber-uebernehmen <code> "
            + "--benutzer <uuid> --wer <uuid> [--ausfuehren]";

    public record Aufruf(MandantCode mandant, UUID ziel, UUID betreiber, boolean schreiben) {}

    private final BestehendenInhaberDokumentieren dokumentieren;
    private final MandantKontext kontext;

    public BestehendenInhaberKommando(BestehendenInhaberDokumentieren dokumentieren, MandantKontext kontext) {
        this.dokumentieren = dokumentieren;
        this.kontext = kontext;
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if ((argumente.size() != 6 && argumente.size() != 7)
                || !argumente.getFirst().equals("inhaber-uebernehmen")
                || !argumente.get(2).equals("--benutzer") || !argumente.get(4).equals("--wer")
                || argumente.size() == 7 && !argumente.get(6).equals("--ausfuehren")) {
            return Optional.empty();
        }
        try {
            var ziel = UUID.fromString(argumente.get(3));
            var betreiber = UUID.fromString(argumente.get(5));
            if (!ziel.toString().equalsIgnoreCase(argumente.get(3))
                    || !betreiber.toString().equalsIgnoreCase(argumente.get(5))) {
                return Optional.empty();
            }
            return Optional.of(new Aufruf(new MandantCode(argumente.get(1)), ziel, betreiber, argumente.size() == 7));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> eingabe = lesen(argumente);
        if (eingabe.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            Aufruf aufruf = eingabe.orElseThrow();
            var ergebnis = kontext.mit(aufruf.mandant(), () -> dokumentieren.ausfuehren(
                    aufruf.mandant(), aufruf.ziel(), aufruf.betreiber(), aufruf.schreiben()));
            aus.println(ergebnis.name());
            return 0;
        } catch (FachFehler e) {
            fehler.println(e.code().name());
            return e.code() == Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR
                    || e.code() == Fehlercode.IDENTITAETSDIENST_FEHLER ? 3 : 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
