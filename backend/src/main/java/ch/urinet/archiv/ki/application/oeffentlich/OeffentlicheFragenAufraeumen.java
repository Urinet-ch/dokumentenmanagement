package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Löschung der gespeicherten Fragen des öffentlichen Chatbots nach der Aufbewahrung (Spec 03, Req 6.5): Standard
 * {@code archiv.webseite.chat.aufbewahrung-tage} (90 Tage), je Mandant einstellbar mit
 * {@code archiv.mandant.<code>.chat-aufbewahrung-tage} (wie {@code eigene-arten}; für {@code vhf-gsk} 30 Tage laut Zusage
 * an den Verband). Erlaubt sind {@value #MIN} bis {@value #MAX} Tage; ein Wert ausserhalb oder kein Text einer ganzen
 * Zahl bricht den Start ab (die Fragen sollen weder sofort noch nie verschwinden). Nicht in einer laufenden Transaktion
 * aufrufen.
 */
@ApplicationScoped
public class OeffentlicheFragenAufraeumen {

    public static final int MIN = 1;
    public static final int MAX = 365;
    private static final Pattern SCHLUESSEL = Pattern.compile("^archiv\\.mandant\\.([a-z0-9]+(?:-[a-z0-9]+)*)\\.chat-aufbewahrung-tage$");

    private final MandantKontext kontext;
    private final OeffentlicheFragenSpeicher speicher;
    private final MandantEinstellungen einstellungen;
    private final int standardTage;
    private final Map<String, Integer> jeMandant;

    @Inject
    public OeffentlicheFragenAufraeumen(MandantKontext kontext, OeffentlicheFragenSpeicher speicher,
                                        MandantEinstellungen einstellungen,
                                        @ConfigProperty(name = "archiv.webseite.chat.aufbewahrung-tage") String standard,
                                        Config konfiguration) {
        this(kontext, speicher, einstellungen, standard, konfiguration.getPropertyNames(),
                name -> konfiguration.getOptionalValue(name, String.class));
    }

    OeffentlicheFragenAufraeumen(MandantKontext kontext, OeffentlicheFragenSpeicher speicher,
                                 MandantEinstellungen einstellungen, String standard,
                                 Iterable<String> namen, Function<String, Optional<String>> wert) {
        this.kontext = kontext;
        this.speicher = speicher;
        this.einstellungen = einstellungen;
        this.standardTage = tage("archiv.webseite.chat.aufbewahrung-tage", standard);
        Map<String, Integer> eigene = new HashMap<>();
        for (String name : namen) {
            Matcher treffer = SCHLUESSEL.matcher(name);
            if (treffer.matches()) {
                eigene.put(treffer.group(1), tage(name, wert.apply(name).orElse("")));
            }
        }
        this.jeMandant = Map.copyOf(eigene);
    }

    /** Ganze Zahl von {@value #MIN} bis {@value #MAX}; sonst Abbruch mit dem Schlüssel, nie mit dem Wert. */
    static int tage(String schluessel, String wert) {
        try {
            int tage = Integer.parseInt(wert.strip());
            if (tage >= MIN && tage <= MAX) {
                return tage;
            }
        } catch (NumberFormatException e) {
            // fällt in die Meldung unten
        }
        throw new IllegalArgumentException(schluessel + " muss eine ganze Zahl von " + MIN + " bis " + MAX + " sein");
    }

    /** Aufbewahrung des Mandanten in Tagen. */
    public int tage(MandantCode mandant) {
        return jeMandant.getOrDefault(mandant.wert(), standardTage);
    }

    /** Grenze für {@code jetzt}: was davor liegt, wird gelöscht. */
    public Instant grenze(MandantCode mandant, Instant jetzt) {
        return jetzt.atOffset(ZoneOffset.UTC).minus(Period.ofDays(tage(mandant))).toInstant();
    }

    /** Anzahl der gelöschten Fragen des Mandanten. */
    public int aufraeumen(MandantCode mandant, Instant jetzt) {
        return kontext.mit(mandant, () -> {
            Integer eigeneTage = einstellungen.aufbewahrung().webseitenChatTage();
            if (eigeneTage != null && (eigeneTage < MIN || eigeneTage > MAX)) {
                throw new IllegalStateException("Ungültige Webseiten-Chat-Aufbewahrung in der Mandantenkonfiguration");
            }
            Instant grenze = eigeneTage == null ? grenze(mandant, jetzt)
                    : jetzt.atOffset(ZoneOffset.UTC).minusDays(eigeneTage).toInstant();
            return speicher.loeschenVor(grenze);
        });
    }
}
