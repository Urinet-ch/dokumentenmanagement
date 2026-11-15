package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Gibt verwaiste Aufträge frei (Req 5.7, design.md «Verarbeitung»): {@code LAEUFT} ohne Lebenszeichen seit der
 * Zeitgrenze des Typs, z. B. nach einem Absturz des Workers. Das zählt als Fehlversuch mit
 * {@code fehler = 'verwaist fehler-id=…'} und derselben Wartezeit wie ein Fehler; nach dem dritten Fehlversuch ist der
 * Auftrag {@code FEHLER} (Req 5.4). Den Hook ruft er dann nur für aktive Mandanten sofort auf; für inaktive holt ihn
 * die Nachbearbeitung nach, sobald der Mandant wieder aktiv ist. Lässt sich ein einzelner Auftrag nicht freigeben (z. B.
 * Zeilensperre länger als die Sperr-Wartezeit), protokolliert er das und macht mit den übrigen weiter.
 * <p>
 * Danach holt er den Hook {@link AuftragHandler#nachLetztemFehlversuch} für {@code FEHLER}-Aufträge nach, deren
 * Nachbearbeitung fehlt (z. B. Absturz zwischen dem Speichern von {@code FEHLER} und dem Hook), sobald ihr Ende länger
 * als die Zeitgrenze des Typs zurückliegt. Der Hook belegt dafür einen Platz des Workers und läuft nicht, solange in
 * dieser JVM noch ein Thread für den Auftrag lebt; scheitert er, folgt er mit Wartezeit (2^n Minuten) erneut, nach
 * {@link ch.urinet.archiv.verarbeitung.domain.Nachbearbeitung#MAX_VERSUCHE} Fehlschlägen nicht mehr. Aufträge inaktiver
 * Mandanten pausieren, auch ihre Nachbearbeitung.
 * <p>
 * In Stufe P (Restore-Probe, Req 16.24) räumt er nur Aufträge des Typs {@code PRUEFLAUF} auf: Wiederhergestellte
 * Aufträge anderer Typen bleiben unverändert liegen (kein Versuch gezählt, kein {@code FEHLER}, kein Hook).
 */
@ApplicationScoped
public class AuftragAufraeumen {

    private static final Logger LOG = Logger.getLogger(AuftragAufraeumen.class);

    private final AuftragSchlange schlange;
    private final AuftragHandlerVerzeichnis verzeichnis;
    private final AuftragArbeiter arbeiter;
    private final boolean nurPrueflauf;

    @Inject
    public AuftragAufraeumen(AuftragSchlange schlange, AuftragHandlerVerzeichnis verzeichnis,
                             AuftragArbeiter arbeiter,
                             @ConfigProperty(name = "archiv.stufe", defaultValue = "B") String stufe) {
        this.schlange = schlange;
        this.verzeichnis = verzeichnis;
        this.arbeiter = arbeiter;
        this.nurPrueflauf = Stufe.aus(Optional.ofNullable(stufe)) == Stufe.P;
    }

    /** Ohne Stufenangabe wie Stufe B (alle Typen). */
    public AuftragAufraeumen(AuftragSchlange schlange, AuftragHandlerVerzeichnis verzeichnis,
                             AuftragArbeiter arbeiter) {
        this(schlange, verzeichnis, arbeiter, "B");
    }

    /** Zeitgrenzen der aufzuräumenden Typen; in Stufe P nur {@code PRUEFLAUF}. */
    private Map<AuftragTyp, Duration> zeitgrenzen() {
        Map<AuftragTyp, Duration> alle = verzeichnis.zeitgrenzen();
        if (!nurPrueflauf) {
            return alle;
        }
        Duration grenze = alle.get(AuftragArbeiter.PRUEFLAUF);
        return grenze == null ? Map.of() : Map.of(AuftragArbeiter.PRUEFLAUF, grenze);
    }

    /** Gibt verwaiste Aufträge frei und holt fehlende Nachbearbeitungen nach; liefert die Zahl freigegebener Aufträge. */
    public int aufraeumen() {
        if (zeitgrenzen().isEmpty()) {
            return 0;
        }
        int freigegeben = verwaisteFreigeben();
        nachbearbeitungNachholen();
        return freigegeben;
    }

    /** Zahl der Aufträge, deren Hook nach dem letzten Fehlversuch jetzt erfolgreich nachgeholt wurde. */
    public int nachbearbeitungNachholen() {
        if (zeitgrenzen().isEmpty()) {
            return 0;
        }
        int nachgeholt = 0;
        for (Fehlgeschlagen fehlgeschlagen : schlange.ohneNachbearbeitung(zeitgrenzen())) {
            Auftrag auftrag = fehlgeschlagen.auftrag();
            LOG.infof("Auftrag %d (%s): Nachbearbeitung nach Fehler %s wird nachgeholt", auftrag.id(), auftrag.typ(),
                    fehlgeschlagen.fehler().fehlerId());
            if (arbeiter.nachbearbeiten(auftrag, fehlgeschlagen.fehler().fehlerId(),
                    fehlgeschlagen.nachbearbeitungVersuche())) {
                nachgeholt++;
            }
        }
        return nachgeholt;
    }

    private int verwaisteFreigeben() {
        int freigegeben = 0;
        for (Verwaist verwaist : schlange.verwaiste(zeitgrenzen())) {
            Auftrag auftrag = verwaist.auftrag();
            int versuch = auftrag.versuche() + 1;
            Optional<Duration> wartezeit = Wiederholung.nachFehlversuch(versuch);
            AuftragFehler fehler = AuftragFehler.neu(AuftragArbeiter.VERWAIST);
            try {
                if (!schlange.verwaistFreigeben(auftrag, verzeichnis.zeitgrenze(auftrag.typ()), fehler.text(),
                        wartezeit)) {
                    continue;
                }
            } catch (RuntimeException e) {
                // bleibt LAEUFT und kommt beim nächsten Aufräumen wieder
                LOG.warnf("Auftrag %d (%s): verwaist, nicht freigegeben, ursache=%s", auftrag.id(), auftrag.typ(),
                        Ursachen.kette(e));
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
                if (verwaist.mandantAktiv()) {
                    arbeiter.nachbearbeiten(auftrag, fehler.fehlerId(), 0);
                }
            }
        }
        return freigegeben;
    }
}
