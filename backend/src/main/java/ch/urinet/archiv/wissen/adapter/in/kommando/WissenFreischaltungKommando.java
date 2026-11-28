package ch.urinet.archiv.wissen.adapter.in.kommando;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenFreischalten;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * {@code archiv mandant wissen <code> [ein|aus]} (Spec 04, Req 22.2): ohne {@code ein}/{@code aus} nur den Zustand
 * ausgeben. Exit-Codes: 0 ok (auch wenn der Zustand schon dem Wunsch entspricht), 1 Mandant unbekannt oder nicht aktiv,
 * 2 falscher Aufruf, 3 technischer Fehler; bei 1 bis 3 ändert sich nichts.
 */
@ApplicationScoped
public class WissenFreischaltungKommando {

    public static final String AUFRUF = "Aufruf: archiv mandant wissen <code> [ein|aus]";

    public record Aufruf(MandantCode mandant, Optional<Boolean> freigeschaltet) {
    }

    private final WissenFreischalten freischalten;

    public WissenFreischaltungKommando(WissenFreischalten freischalten) {
        this.freischalten = freischalten;
    }

    /** {@code argumente} beginnt mit {@code wissen}. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 2 || argumente.size() > 3 || !argumente.getFirst().equals("wissen")) {
            return Optional.empty();
        }
        MandantCode code;
        try {
            code = new MandantCode(argumente.get(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (argumente.size() == 2) {
            return Optional.of(new Aufruf(code, Optional.empty()));
        }
        return switch (argumente.get(2)) {
            case "ein" -> Optional.of(new Aufruf(code, Optional.of(true)));
            case "aus" -> Optional.of(new Aufruf(code, Optional.of(false)));
            default -> Optional.empty();
        };
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> gelesen = lesen(argumente);
        if (gelesen.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        Aufruf aufruf = gelesen.get();
        String code = aufruf.mandant().wert();
        try {
            if (aufruf.freigeschaltet().isEmpty()) {
                Optional<Boolean> zustand = freischalten.zustand(aufruf.mandant());
                if (zustand.isEmpty()) {
                    fehler.println("Mandant unbekannt oder nicht aktiv: " + code);
                    return 1;
                }
                aus.printf("Mandant %s: Wissen %s.%n", code, zustand.get() ? "freigeschaltet" : "nicht freigeschaltet");
                return 0;
            }
            Optional<WissenFreischalten.Ergebnis> ergebnis =
                    freischalten.setzen(aufruf.mandant(), aufruf.freigeschaltet().get());
            if (ergebnis.isEmpty()) {
                fehler.println("Mandant unbekannt oder nicht aktiv: " + code);
                return 1;
            }
            aus.println(switch (ergebnis.get()) {
                case FREIGESCHALTET -> "Mandant " + code + ": Wissen freigeschaltet; wirkt nach spätestens 60 Sekunden.";
                case ABGESCHALTET -> "Mandant " + code + ": Wissen abgeschaltet; wirkt nach spätestens 60 Sekunden. "
                        + "Es wurden keine Daten gelöscht.";
                case UNVERAENDERT -> "Mandant " + code + ": Wissen ist bereits "
                        + (aufruf.freigeschaltet().get() ? "freigeschaltet" : "abgeschaltet") + "; nichts geändert.";
            });
            return 0;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
