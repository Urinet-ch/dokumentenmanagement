package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Die eine Stelle für «Wissen freigeschaltet» (Spec 04, Req 22; design.md «Wissen je Mandant freischalten»): REST-Sperre,
 * Chat, Suche, öffentlicher Wissenschat, Auftragsarbeiter, Lint-Zeitplan, Wochenbericht und {@code GET
 * /mandant/funktionen} fragen nur hier. Der Zustand wird höchstens {@value #HOECHSTENS_SEKUNDEN} Sekunden
 * zwischengespeichert, damit das Betreiber-Kommando ohne Neustart wirkt (Req 22.2); kann er nicht gelesen werden oder
 * ist der Mandant unbekannt, gilt «nicht freigeschaltet» (fail closed). Kein Sonderfall für einen Mandantencode.
 */
@ApplicationScoped
public class Wissensfreischaltung {

    static final long HOECHSTENS_SEKUNDEN = 60;
    /** Grund, mit dem laufende und wartende Aufträge der Wissensbasis nach dem Abschalten enden (Req 22.9). */
    public static final String GRUND_AUSGESCHALTET = "WISSEN_AUSGESCHALTET";
    private static final Logger LOG = Logger.getLogger(Wissensfreischaltung.class);

    private record Eintrag(boolean freigeschaltet, long bisNanos) {
    }

    private final WissensfreischaltungPort port;
    private final MandantKontext kontext;
    private final MandantAusAnmeldung anmeldung;
    private final long gueltigNanos;
    private final LongSupplier uhr;
    private final Map<MandantCode, Eintrag> zwischenspeicher = new ConcurrentHashMap<>();

    @Inject
    public Wissensfreischaltung(WissensfreischaltungPort port, MandantKontext kontext, MandantAusAnmeldung anmeldung,
                                @ConfigProperty(name = "archiv.wissen.freischaltung-zwischenspeicher",
                                        defaultValue = "60s") Duration zwischenspeicher) {
        this(port, kontext, anmeldung, zwischenspeicher, System::nanoTime);
    }

    Wissensfreischaltung(WissensfreischaltungPort port, MandantKontext kontext, MandantAusAnmeldung anmeldung,
                         Duration zwischenspeicher, LongSupplier uhr) {
        if (zwischenspeicher.isNegative() || zwischenspeicher.compareTo(Duration.ofSeconds(HOECHSTENS_SEKUNDEN)) > 0) {
            throw new IllegalArgumentException(
                    "archiv.wissen.freischaltung-zwischenspeicher muss zwischen 0 und 60 Sekunden liegen");
        }
        this.port = port;
        this.kontext = kontext;
        this.anmeldung = anmeldung;
        this.gueltigNanos = zwischenspeicher.toNanos();
        this.uhr = uhr;
    }

    /** Ist Wissen für diesen Mandanten freigeschaltet? Fehler und unbekannte Mandanten ergeben {@code false}. */
    public boolean freigeschaltet(MandantCode mandant) {
        long jetzt = uhr.getAsLong();
        Eintrag eintrag = zwischenspeicher.get(mandant);
        if (eintrag != null && jetzt - eintrag.bisNanos() < 0) {
            return eintrag.freigeschaltet();
        }
        Optional<Boolean> gelesen;
        try {
            gelesen = port.lesen(mandant);
        } catch (RuntimeException e) {
            // nicht zwischenspeichern: der nächste Aufruf versucht es erneut, bis dahin gesperrt
            LOG.warnf("Freischaltung der Wissensbasis für %s nicht lesbar (%s); gilt als nicht freigeschaltet",
                    mandant.wert(), e.getClass().getSimpleName());
            return false;
        }
        if (gelesen.isEmpty()) {
            // unbekannt: nicht zwischenspeichern, damit ein eben angelegter Mandant nicht 60 s als gesperrt gilt
            return false;
        }
        boolean wert = gelesen.get();
        if (gueltigNanos > 0) {
            zwischenspeicher.put(mandant, new Eintrag(wert, jetzt + gueltigNanos));
        }
        return wert;
    }

    /** Mandant der Anfrage (Anmeldung) bzw. des Mandanten-Kontexts (Auftrag, Kommando); ohne Mandant {@code false}. */
    public boolean aktuell() {
        return mandant().map(this::freigeschaltet).orElse(false);
    }

    /** Wie {@link #aktuell()}, aber nicht freigeschaltet ergibt 403 {@code WISSEN_NICHT_FREIGESCHALTET} (Req 22.6). */
    public void pruefen() {
        if (!aktuell()) {
            throw new FachFehler(Fehlercode.WISSEN_NICHT_FREIGESCHALTET);
        }
    }

    /** Unmittelbar vor jedem Modellaufruf der Wissensbasis (Req 22.9): nicht freigeschaltet → {@link WissenAusgeschaltet}. */
    public void modellaufrufPruefen() {
        if (!aktuell()) {
            throw new WissenAusgeschaltet();
        }
    }

    /** Nach einer Änderung im selben Prozess sofort neu lesen; andere Prozesse lesen spätestens nach 60 s neu. */
    public void vergessen(MandantCode mandant) {
        zwischenspeicher.remove(mandant);
    }

    private Optional<MandantCode> mandant() {
        try {
            Optional<MandantCode> imKontext = kontext.aktuell();
            if (imKontext.isPresent()) {
                return imKontext;
            }
            return anmeldung.ermitteln() instanceof MandantAusAnmeldung.Gueltig gueltig
                    ? Optional.of(gueltig.code()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
