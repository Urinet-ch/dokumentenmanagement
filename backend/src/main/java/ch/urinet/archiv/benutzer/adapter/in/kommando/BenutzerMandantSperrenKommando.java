package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.benutzer.application.BenutzerMandantSperren;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code archiv mandant benutzer-sperren <code> [--json]}. */
@ApplicationScoped
public class BenutzerMandantSperrenKommando {
    public static final String AUFRUF = "Aufruf: archiv mandant benutzer-sperren <code> [--json]";

    public record Aufruf(MandantCode mandant, boolean json) {
    }

    private final BenutzerMandantSperren sperren;
    private final ObjectMapper json;

    public BenutzerMandantSperrenKommando(BenutzerMandantSperren sperren, ObjectMapper json) {
        this.sperren = sperren;
        this.json = json;
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 2 || !argumente.getFirst().equals("benutzer-sperren")) {
            return Optional.empty();
        }
        MandantCode code;
        try {
            code = new MandantCode(argumente.get(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (argumente.size() == 2) {
            return Optional.of(new Aufruf(code, false));
        }
        if (argumente.size() == 3 && argumente.get(2).equals("--json")) {
            return Optional.of(new Aufruf(code, true));
        }
        return Optional.empty();
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            Aufruf eingabe = aufruf.orElseThrow();
            BenutzerMandantSperren.Bericht bericht = sperren.ausfuehren(eingabe.mandant());
            if (eingabe.json()) {
                aus.println(json(bericht, eingabe));
            } else if (bericht.geloescht() > 0) {
                aus.printf("Mandant %s: %d Keycloak-Benutzer nach Ablauf der Frist gelöscht.%n",
                        eingabe.mandant().wert(), bericht.geloescht());
            } else {
                aus.printf("Mandant %s: %d Keycloak-Benutzer deaktiviert und Sitzungen beendet; Löschung ab %s.%n",
                        eingabe.mandant().wert(), bericht.gesperrt(), bericht.loeschbarAb());
            }
            return 0;
        } catch (FachFehler e) {
            fehler.println(e.code().name());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }

    private String json(BenutzerMandantSperren.Bericht bericht, Aufruf aufruf) {
        Map<String, Object> ausgabe = Map.of(
                "mandant", aufruf.mandant().wert(),
                "gesperrt", bericht.gesperrt(),
                "geloescht", bericht.geloescht(),
                "loeschbar_ab", bericht.loeschbarAb().toString());
        try {
            return json.writeValueAsString(ausgabe);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON-Ausgabe nicht erzeugbar", e);
        }
    }
}
