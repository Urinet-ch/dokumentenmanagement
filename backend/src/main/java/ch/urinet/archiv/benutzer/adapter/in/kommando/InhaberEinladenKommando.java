package ch.urinet.archiv.benutzer.adapter.in.kommando;

import ch.urinet.archiv.benutzer.application.BenutzerAnlegen;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class InhaberEinladenKommando {
    public static final String AUFRUF = "Aufruf: archiv mandant inhaber-einladen <code> --benutzer <name> "
            + "--email <adresse> --vorname <v> --nachname <n> --wer <name>";
    private static final Set<String> OPTIONEN = Set.of("--benutzer", "--email", "--vorname", "--nachname", "--wer");

    private final BenutzerAnlegen anlegen;
    private final MandantKontext kontext;

    public InhaberEinladenKommando(BenutzerAnlegen anlegen, MandantKontext kontext) {
        this.anlegen = anlegen;
        this.kontext = kontext;
    }

    public record Aufruf(MandantCode mandant, String betreiber, BenutzerAnlegen.BenutzerAnlage eingabe) {
        @Override
        public String toString() {
            return "Aufruf[geschützt]";
        }
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() != 12 || !argumente.getFirst().equals("inhaber-einladen")) {
            return Optional.empty();
        }
        MandantCode code;
        try {
            code = new MandantCode(argumente.get(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Map<String, String> optionen = new HashMap<>();
        for (int i = 2; i < argumente.size(); i += 2) {
            String option = argumente.get(i);
            String wert = argumente.get(i + 1);
            if (!OPTIONEN.contains(option) || wert.isEmpty() || wert.length() > 254
                    || optionen.putIfAbsent(option, wert) != null) {
                return Optional.empty();
            }
        }
        String wer = optionen.get("--wer");
        if (optionen.size() != OPTIONEN.size() || wer == null || !wer.strip().equals(wer)
                || !wer.matches("[^\\p{Cntrl}]{1,80}")) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(code, wer, new BenutzerAnlegen.BenutzerAnlage(optionen.get("--benutzer"),
                optionen.get("--email"), optionen.get("--vorname"), optionen.get("--nachname"), "inhaber")));
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> aufruf = lesen(argumente);
        if (aufruf.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            Aufruf eingabe = aufruf.get();
            aus.println(kontext.mit(eingabe.mandant(), () -> anlegen.erstenInhaberEinladen(eingabe.mandant(),
                    eingabe.betreiber(), eingabe.eingabe())));
            return 0;
        } catch (FachFehler e) {
            fehler.println(e.code().name());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
