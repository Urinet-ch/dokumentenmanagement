package ch.urinet.archiv.protokoll.adapter.in.kommando;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestPruefen;
import ch.urinet.archiv.protokoll.domain.Pruefbericht;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code archiv manifest pruefen <datum> [<bis-datum>] [--mandant <code>]} (Req 15.12): prüft die Tages-Manifeste nur
 * aus der Ablage. Ohne {@code --mandant} alle aktiven Mandanten. Datum im Format {@code JJJJ-MM-TT}.
 * Exit 0 = alles gültig (Meldungen erlaubt), 1 = mindestens ein Fehler, 2 = falscher Aufruf, 3 = technischer Fehler.
 * Der Bericht steht auf stdout und nennt nur Datum, Dienst und Befund, keine Inhalte.
 */
@ApplicationScoped
public class ManifestKommando {

    public static final String AUFRUF = "Aufruf: archiv manifest pruefen <datum JJJJ-MM-TT> [<bis-datum>] [--mandant <code>]";

    private final ManifestPruefen pruefen;
    private final MandantRegister register;

    public ManifestKommando(ManifestPruefen pruefen, MandantRegister register) {
        this.pruefen = pruefen;
        this.register = register;
    }

    public record Aufruf(LocalDate von, LocalDate bis, Optional<MandantCode> mandant) {
    }

    /** Prüft die Argumente nach {@code manifest}, ohne die Anwendung zu brauchen; leer bei falschem Aufruf. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.isEmpty() || !argumente.getFirst().equals("pruefen")) {
            return Optional.empty();
        }
        List<String> daten = new ArrayList<>();
        Optional<MandantCode> mandant = Optional.empty();
        try {
            for (int i = 1; i < argumente.size(); i++) {
                String teil = argumente.get(i);
                if (teil.equals("--mandant")) {
                    if (mandant.isPresent() || i + 1 >= argumente.size()) {
                        return Optional.empty();
                    }
                    mandant = Optional.of(new MandantCode(argumente.get(++i)));
                } else {
                    daten.add(teil);
                }
            }
            if (daten.isEmpty() || daten.size() > 2) {
                return Optional.empty();
            }
            LocalDate von = LocalDate.parse(daten.getFirst());
            LocalDate bis = daten.size() == 2 ? LocalDate.parse(daten.get(1)) : von;
            return bis.isBefore(von) ? Optional.empty() : Optional.of(new Aufruf(von, bis, mandant));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            List<MandantCode> mandanten = aufruf.get().mandant().map(List::of)
                    .orElseGet(() -> register.aktive().stream().map(Mandant::code).toList());
            boolean ok = true;
            for (MandantCode mandant : mandanten) {
                Pruefbericht bericht = pruefen.pruefen(mandant, aufruf.get().von(), aufruf.get().bis());
                aus.printf("Mandant %s: %d Manifest(e) geprüft, %d Fehler, %d Meldung(en)%n", mandant.wert(),
                        bericht.anzahlGeprueft(), bericht.fehler().size(), bericht.meldungen().size());
                bericht.fehler().forEach(text -> aus.println("  FEHLER " + text));
                bericht.meldungen().forEach(text -> aus.println("  MELDUNG " + text));
                ok &= bericht.ok();
            }
            aus.println(ok ? "Ergebnis: gültig" : "Ergebnis: Fehler gefunden");
            return ok ? 0 : 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
