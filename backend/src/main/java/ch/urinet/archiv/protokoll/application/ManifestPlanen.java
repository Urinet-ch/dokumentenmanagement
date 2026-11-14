package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Reiht je aktivem Mandanten einen {@code MANIFEST}-Auftrag bis gestern ein (Req 15.1). Mehrfache Aufträge sind
 * harmlos: Ein Lauf hält die Sperre je Mandant ({@link ManifestDaten#sperren()}), der zweite findet nichts mehr zu tun.
 * <p>
 * Trotzdem wird nichts eingereiht, solange ein Auftrag des Mandanten noch offen ist oder läuft (ein
 * verwaister läuft nach dem Neustart weiter). Beim Start zusätzlich nicht, wenn der jüngste Auftrag erst vor Kurzem
 * ({@code archiv.manifest.start-karenz}) in {@code FEHLER} endete: Sonst wiederholt jeder Neustart dieselbe
 * gescheiterte Arbeit, und ein Prozess, der währenddessen stirbt, kommt nie zur Ruhe. Der Lauf um 03:30 und die
 * Überwachung holen nach. Prüfung und Einreihen laufen je Mandant in einer Transaktion unter der Sperre auf den Typ ({@link ManifestEinreihen}).
 */
@ApplicationScoped
public class ManifestPlanen {

    private static final Logger LOG = Logger.getLogger(ManifestPlanen.class);

    private final MandantRegister register;
    private final MandantKontext kontext;
    private final ManifestEinreihen einreihen;

    public ManifestPlanen(MandantRegister register, MandantKontext kontext, ManifestEinreihen einreihen) {
        this.register = register;
        this.kontext = kontext;
        this.einreihen = einreihen;
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
                Optional<Long> id = kontext.mit(mandant.code(),
                        () -> einreihen.einreihen(mandant.code(), gestern, jetzt, beimStart));
                id.ifPresent(auftrag -> LOG.infof("Manifest bis %s für Mandant %s eingereiht (Auftrag %d)", gestern,
                        mandant.code().wert(), auftrag));
            } catch (RuntimeException e) {
                LOG.errorf("Manifest für Mandant %s nicht eingereiht, ursache=%s", mandant.code().wert(),
                        Ursachen.ketteMitMeldung(e));
            }
        }
    }
}
