package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Gibt verwaiste Aufträge frei (Req 5.7, design.md «Verarbeitung»): {@code LAEUFT} ohne Lebenszeichen seit der
 * Zeitgrenze des Typs, z. B. nach einem Absturz des Workers. Das zählt als Fehlversuch mit
 * {@code fehler = 'verwaist fehler-id=…'} und derselben Wartezeit wie ein Fehler; nach dem dritten Fehlversuch ist der
 * Auftrag {@code FEHLER} (Req 5.4).
 */
@ApplicationScoped
public class AuftragAufraeumen {

    private static final Logger LOG = Logger.getLogger(AuftragAufraeumen.class);

    private final AuftragSchlange schlange;
    private final AuftragHandlerVerzeichnis verzeichnis;
    private final AuftragArbeiter arbeiter;

    public AuftragAufraeumen(AuftragSchlange schlange, AuftragHandlerVerzeichnis verzeichnis,
                             AuftragArbeiter arbeiter) {
        this.schlange = schlange;
        this.verzeichnis = verzeichnis;
        this.arbeiter = arbeiter;
    }

    /** Anzahl freigegebener Aufträge. */
    public int aufraeumen() {
        if (verzeichnis.typen().isEmpty()) {
            return 0;
        }
        int freigegeben = 0;
        for (Auftrag auftrag : schlange.verwaiste(verzeichnis.zeitgrenzen())) {
            int versuch = auftrag.versuche() + 1;
            Optional<Duration> wartezeit = Wiederholung.nachFehlversuch(versuch);
            String fehlerId = UUID.randomUUID().toString();
            if (!schlange.verwaistFreigeben(auftrag, verzeichnis.zeitgrenze(auftrag.typ()),
                    AuftragArbeiter.VERWAIST + " fehler-id=" + fehlerId, wartezeit)) {
                continue;
            }
            freigegeben++;
            if (wartezeit.isPresent()) {
                LOG.warnf("Auftrag %d (%s, Mandant %s): Versuch %d von %d verwaist, Fehler %s, freigegeben, "
                                + "nächster Versuch in %s", auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch,
                        Wiederholung.MAX_VERSUCHE, fehlerId, wartezeit.get());
            } else {
                LOG.errorf("Auftrag %d (%s, Mandant %s): Versuch %d von %d verwaist, Fehler %s, Status FEHLER",
                        auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch, Wiederholung.MAX_VERSUCHE,
                        fehlerId);
                arbeiter.nachLetztemFehlversuch(auftrag);
            }
        }
        return freigegeben;
    }
}
