package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.AuftragStand;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Prüfung und Einreihen des {@code MANIFEST}-Auftrags eines Mandanten in einer Transaktion (Aufruf im
 * Mandanten-Kontext durch {@link ManifestPlanen}). Die Sperre auf den Auftragstyp ordnet gleichzeitige Planer, die
 * Prüfung sieht alle offenen oder laufenden Aufträge, nicht nur den jüngsten.
 */
@ApplicationScoped
public class ManifestEinreihen {

    private static final Logger LOG = Logger.getLogger(ManifestEinreihen.class);

    private final AuftragService auftraege;
    private final AuftragStand stand;
    private final Duration startKarenz;

    public ManifestEinreihen(AuftragService auftraege, AuftragStand stand,
                             @ConfigProperty(name = "archiv.manifest.start-karenz", defaultValue = "PT1H")
                             Duration startKarenz) {
        this.auftraege = auftraege;
        this.stand = stand;
        this.startKarenz = startKarenz;
    }

    /** ID des neuen Auftrags; leer, wenn nichts eingereiht wurde. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Long> einreihen(MandantCode mandant, LocalDate gestern, Instant jetzt, boolean beimStart) {
        if (auftraege.offen(ManifestErzeugen.TYP, Optional.empty(), true).isPresent()) {
            LOG.infof("Manifest für Mandant %s nicht eingereiht: ein Auftrag ist offen oder läuft", mandant.wert());
            return Optional.empty();
        }
        if (beimStart && inKarenz(mandant, jetzt)) {
            return Optional.empty();
        }
        return Optional.of(auftraege.einreihen(ManifestErzeugen.TYP,
                new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, gestern.toEpochDay()))));
    }

    private boolean inKarenz(MandantCode mandant, Instant jetzt) {
        Optional<AuftragStand.Juengster> juengster = stand.juengster(mandant, ManifestErzeugen.TYP);
        boolean inKarenz = juengster.isPresent() && juengster.get().gescheitert() && juengster.get().beendet()
                .filter(ende -> ende.plus(startKarenz).isAfter(jetzt)).isPresent();
        if (inKarenz) {
            LOG.infof("Manifest für Mandant %s beim Start nicht eingereiht: der letzte Auftrag ist innerhalb von %s "
                    + "gescheitert (archiv.manifest.start-karenz)", mandant.wert(), startKarenz);
        }
        return inKarenz;
    }
}
