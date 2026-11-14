package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.AuftragStand;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Reiht je aktivem Mandanten einen {@code MANIFEST}-Auftrag bis gestern ein (Req 15.1). Mehrfache Aufträge sind
 * harmlos: Ein Lauf hält die Sperre je Mandant ({@link ManifestDaten#sperren()}), der zweite findet nichts mehr zu tun.
 * <p>
 * Trotzdem wird nichts eingereiht, solange der jüngste Auftrag des Mandanten noch offen ist oder läuft (ein
 * verwaister läuft nach dem Neustart weiter). Beim Start zusätzlich nicht, wenn der jüngste Auftrag erst vor Kurzem
 * ({@code archiv.manifest.start-karenz}) in {@code FEHLER} endete: Sonst wiederholt jeder Neustart dieselbe
 * gescheiterte Arbeit, und ein Prozess, der währenddessen stirbt, kommt nie zur Ruhe. Der Lauf um 03:30 und die
 * Überwachung holen nach. Prüfung und Einreihen sind nicht atomar; ein seltener Doppelauftrag bleibt harmlos (s. o.).
 */
@ApplicationScoped
public class ManifestPlanen {

    private static final Logger LOG = Logger.getLogger(ManifestPlanen.class);

    private final MandantRegister register;
    private final MandantKontext kontext;
    private final AuftragService auftraege;
    private final AuftragStand stand;
    private final Duration startKarenz;

    public ManifestPlanen(MandantRegister register, MandantKontext kontext, AuftragService auftraege,
                          AuftragStand stand,
                          @ConfigProperty(name = "archiv.manifest.start-karenz", defaultValue = "PT1H")
                          Duration startKarenz) {
        this.register = register;
        this.kontext = kontext;
        this.auftraege = auftraege;
        this.stand = stand;
        this.startKarenz = startKarenz;
    }

    /** Planung des Zeitplans (03:30). */
    public void planen(Instant jetzt) {
        planen(jetzt, false);
    }

    /** Planung beim Start; mit Karenz nach einem Fehlschlag. */
    public void planenBeimStart(Instant jetzt) {
        planen(jetzt, true);
    }

    private void planen(Instant jetzt, boolean beimStart) {
        LocalDate gestern = ManifestFenster.tag(jetzt).minusDays(1);
        for (Mandant mandant : register.aktive()) {
            try {
                if (uebersprungen(mandant, jetzt, beimStart)) {
                    continue;
                }
                long id = kontext.mit(mandant.code(), () -> auftraege.einreihen(ManifestErzeugen.TYP,
                        new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, gestern.toEpochDay()))));
                LOG.infof("Manifest bis %s für Mandant %s eingereiht (Auftrag %d)", gestern, mandant.code().wert(), id);
            } catch (RuntimeException e) {
                LOG.errorf("Manifest für Mandant %s nicht eingereiht, ursache=%s", mandant.code().wert(),
                        Ursachen.ketteMitMeldung(e));
            }
        }
    }

    private boolean uebersprungen(Mandant mandant, Instant jetzt, boolean beimStart) {
        Optional<AuftragStand.Juengster> juengster = stand.juengster(mandant.code(), ManifestErzeugen.TYP);
        if (juengster.isEmpty()) {
            return false;
        }
        if (juengster.get().offenOderLaeuft()) {
            LOG.infof("Manifest für Mandant %s nicht eingereiht: ein Auftrag ist offen oder läuft", mandant.code().wert());
            return true;
        }
        boolean inKarenz = beimStart && juengster.get().gescheitert() && juengster.get().beendet()
                .filter(ende -> ende.plus(startKarenz).isAfter(jetzt)).isPresent();
        if (inKarenz) {
            LOG.infof("Manifest für Mandant %s beim Start nicht eingereiht: der letzte Auftrag ist innerhalb von %s "
                    + "gescheitert (archiv.manifest.start-karenz)", mandant.code().wert(), startKarenz);
        }
        return inKarenz;
    }
}
