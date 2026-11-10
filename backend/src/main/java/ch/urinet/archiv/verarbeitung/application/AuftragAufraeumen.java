package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Gibt verwaiste Aufträge frei (Req 5.7, design.md «Verarbeitung»): {@code LAEUFT} ohne Lebenszeichen seit der
 * Zeitgrenze des Typs, z. B. nach einem Absturz des Workers. Das zählt als Fehlversuch mit
 * {@code fehler = 'verwaist fehler-id=…'} und derselben Wartezeit wie ein Fehler; nach dem dritten Fehlversuch ist der
 * Auftrag {@code FEHLER} (Req 5.4).
 * <p>
 * Danach holt er den Hook {@link AuftragHandler#nachLetztemFehlversuch} für {@code FEHLER}-Aufträge nach, deren
 * Nachbearbeitung fehlt (z. B. Absturz zwischen dem Speichern von {@code FEHLER} und dem Hook), sobald ihr Ende länger
 * als die Zeitgrenze des Typs zurückliegt. Aufträge inaktiver Mandanten pausieren, auch ihre Nachbearbeitung.
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

    /** Gibt verwaiste Aufträge frei und holt fehlende Nachbearbeitungen nach; liefert die Zahl freigegebener Aufträge. */
    public int aufraeumen() {
        if (verzeichnis.typen().isEmpty()) {
            return 0;
        }
        int freigegeben = verwaisteFreigeben();
        nachbearbeitungNachholen();
        return freigegeben;
    }

    /** Zahl der Aufträge, deren Hook nach dem letzten Fehlversuch jetzt erfolgreich nachgeholt wurde. */
    public int nachbearbeitungNachholen() {
        if (verzeichnis.typen().isEmpty()) {
            return 0;
        }
        int nachgeholt = 0;
        for (Fehlgeschlagen fehlgeschlagen : schlange.ohneNachbearbeitung(verzeichnis.zeitgrenzen())) {
            Auftrag auftrag = fehlgeschlagen.auftrag();
            LOG.infof("Auftrag %d (%s): Nachbearbeitung nach Fehler %s wird nachgeholt", auftrag.id(), auftrag.typ(),
                    fehlgeschlagen.fehler().fehlerId());
            if (arbeiter.nachbearbeiten(auftrag, fehlgeschlagen.fehler().fehlerId())) {
                nachgeholt++;
            }
        }
        return nachgeholt;
    }

    private int verwaisteFreigeben() {
        int freigegeben = 0;
        for (Auftrag auftrag : schlange.verwaiste(verzeichnis.zeitgrenzen())) {
            int versuch = auftrag.versuche() + 1;
            Optional<Duration> wartezeit = Wiederholung.nachFehlversuch(versuch);
            AuftragFehler fehler = AuftragFehler.neu(AuftragArbeiter.VERWAIST);
            if (!schlange.verwaistFreigeben(auftrag, verzeichnis.zeitgrenze(auftrag.typ()), fehler.text(),
                    wartezeit)) {
                continue;
            }
            freigegeben++;
            if (wartezeit.isPresent()) {
                LOG.warnf("Auftrag %d (%s, Mandant %s): Versuch %d von %d verwaist, Fehler %s, freigegeben, "
                                + "nächster Versuch in %s", auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch,
                        Wiederholung.MAX_VERSUCHE, fehler.fehlerId(), wartezeit.get());
            } else {
                LOG.errorf("Auftrag %d (%s, Mandant %s): Versuch %d von %d verwaist, Fehler %s, Status FEHLER",
                        auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch, Wiederholung.MAX_VERSUCHE,
                        fehler.fehlerId());
                arbeiter.nachbearbeiten(auftrag, fehler.fehlerId());
            }
        }
        return freigegeben;
    }
}
