package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Reiht je aktivem Mandanten einen {@code MANIFEST}-Auftrag bis gestern ein (Req 15.1). Mehrfache Aufträge sind
 * harmlos: Ein Lauf hält die Sperre je Mandant ({@link ManifestDaten#sperren()}), der zweite findet nichts mehr zu tun.
 */
@ApplicationScoped
public class ManifestPlanen {

    private static final Logger LOG = Logger.getLogger(ManifestPlanen.class);

    private final MandantRegister register;
    private final MandantKontext kontext;
    private final AuftragService auftraege;

    public ManifestPlanen(MandantRegister register, MandantKontext kontext, AuftragService auftraege) {
        this.register = register;
        this.kontext = kontext;
        this.auftraege = auftraege;
    }

    public void planen(Instant jetzt) {
        LocalDate gestern = ManifestFenster.tag(jetzt).minusDays(1);
        for (Mandant mandant : register.aktive()) {
            try {
                long id = kontext.mit(mandant.code(), () -> auftraege.einreihen(ManifestErzeugen.TYP,
                        new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, gestern.toEpochDay()))));
                LOG.infof("Manifest bis %s für Mandant %s eingereiht (Auftrag %d)", gestern, mandant.code().wert(), id);
            } catch (RuntimeException e) {
                LOG.errorf("Manifest für Mandant %s nicht eingereiht: %s", mandant.code().wert(),
                        e.getClass().getSimpleName());
            }
        }
    }
}
