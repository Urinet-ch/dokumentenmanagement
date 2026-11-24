package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.benutzer.application.BenutzerAbgleichen;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code archiv benutzer abgleichen <code> [--pruefen] [--json]}. */
@ApplicationScoped
public class BenutzerAbgleichenKommando {
    public static final String AUFRUF = "Aufruf: archiv benutzer abgleichen <code> [--pruefen] [--json]";

    public record Aufruf(MandantCode mandant, boolean pruefen, boolean json) {
    }

    private final BenutzerAbgleichen abgleichen;
    private final ObjectMapper json;

    public BenutzerAbgleichenKommando(BenutzerAbgleichen abgleichen, ObjectMapper json) {
        this.abgleichen = abgleichen;
        this.json = json;
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 2 || !argumente.getFirst().equals("abgleichen")) {
            return Optional.empty();
        }
        MandantCode code;
        try {
            code = new MandantCode(argumente.get(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean pruefen = false;
        boolean json = false;
        for (String option : argumente.subList(2, argumente.size())) {
            switch (option) {
                case "--pruefen" -> {
                    if (pruefen) {
                        return Optional.empty();
                    }
                    pruefen = true;
                }
                case "--json" -> {
                    if (json) {
                        return Optional.empty();
                    }
                    json = true;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Aufruf(code, pruefen, json));
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            Aufruf eingabe = aufruf.orElseThrow();
            BenutzerAbgleichen.Bericht bericht = abgleichen.ausfuehren(eingabe.mandant(), eingabe.pruefen());
            if (eingabe.json()) {
                aus.println(json(bericht, eingabe));
            } else {
                aus.printf("Abgleich %s: %d Abweichungen, %d Übernahmen%s%n",
                        eingabe.mandant().wert(), bericht.abweichungen().size(),
                        eingabe.pruefen() ? bericht.uebernahmeGeplant() : bericht.uebernommen(),
                        eingabe.pruefen() ? " geplant" : "");
                bericht.abweichungen().forEach(befund ->
                        aus.printf("%s %s%n", befund.code(), befund.benutzer()));
            }
            return bericht.abweichungen().isEmpty() ? 0 : 1;
        } catch (FachFehler e) {
            fehler.println(e.code().name());
            return exitCode(e);
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }

    }

    static int exitCode(FachFehler fehler) {
        return fehler.code() == ch.urinet.archiv.gemeinsam.domain.Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR
                ? 3 : 1;
    }

    private String json(BenutzerAbgleichen.Bericht bericht, Aufruf aufruf) {
        Map<String, Object> ausgabe = Map.of(
                "mandant", aufruf.mandant().wert(),
                "abweichungen", bericht.abweichungen().stream()
                        .map(befund -> Map.of("code", befund.code(), "benutzer", befund.benutzer().toString()))
                        .toList(),
                "uebernahme_geplant", bericht.uebernahmeGeplant(),
                "uebernommen", bericht.uebernommen());
        try {
            return json.writeValueAsString(ausgabe);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON-Ausgabe nicht erzeugbar", e);
        }
    }
}
