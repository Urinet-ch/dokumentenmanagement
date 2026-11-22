package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.wissen.application.WissenOkfExportPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Worker für {@code MANDANT_EXPORT}: erzeugt das ZIP je Mandant und prüft dessen Abschluss. */
@ApplicationScoped
public class MandantVollexportHandler implements AuftragHandler {

    private static final Logger LOG = Logger.getLogger(MandantVollexportHandler.class);

    private final MandantVollexport speichern;
    private final MandantVollexportRepository repository;
    private final MandantVollexportDaten daten;
    private final AblagePort ablage;
    private final WissenOkfExportPort wissen;
    private final MandantVollexportKonfiguration konfiguration;
    private final Path arbeitsverzeichnis;

    @Inject
    public MandantVollexportHandler(MandantVollexport speichern, MandantVollexportRepository repository,
                                    MandantVollexportDaten daten, AblagePort ablage, WissenOkfExportPort wissen,
                                    MandantVollexportKonfiguration konfiguration,
                                    @ConfigProperty(name = "archiv.arbeit.verzeichnis") Path arbeitsverzeichnis) {
        this.speichern = speichern;
        this.repository = repository;
        this.daten = daten;
        this.ablage = ablage;
        this.wissen = wissen;
        this.konfiguration = konfiguration;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
    }

    public MandantVollexportHandler(MandantVollexport speichern, MandantVollexportRepository repository,
                                    MandantVollexportDaten daten, AblagePort ablage, WissenOkfExportPort wissen,
                                    Path arbeitsverzeichnis) {
        this(speichern, repository, daten, ablage, wissen, MandantVollexportKonfiguration.leer(), arbeitsverzeichnis);
    }

    @Override
    public AuftragTyp typ() {
        return MandantVollexport.TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(MandantVollexport.EXPORT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne Export-ID"));
        MandantVollexportRepository.Vollexport export = repository.laden(id)
                .orElseThrow(() -> new IllegalStateException("Mandanten-Vollexport fehlt"));
        if (export.status() != MandantVollexportRepository.Status.OFFEN) {
            LOG.infof("Mandanten-Vollexport %s schon %s", id, export.status());
            return;
        }

        MandantCode mandant = auftrag.mandant();
        ablage.exportLebenszyklusSichern(mandant);
        MandantVollexportSchreiber schreiber = new MandantVollexportSchreiber(daten, ablage, wissen, konfiguration);
        AbgelegteDatei zip;
        int anzahl;
        try (Exportziel ziel = ablage.exportSchreiben(mandant, id)) {
            anzahl = schreiber.schreiben(mandant, id, arbeitsverzeichnis, ziel.strom());
            zip = ziel.abschliessen();
        } catch (MandantVollexportKonfiguration.ZuGross e) {
            UUID fehlerId = UUID.randomUUID();
            if (speichern.fehler(auftrag, id, fehlerId,
                    MandantVollexportRepository.Fehlerart.KONFIGURATION_ZU_GROSS)) {
                LOG.warnf("Mandanten-Vollexport %s abgelehnt: fehler-id=%s fehlercode=KONFIGURATION_ZU_GROSS",
                        id, fehlerId);
            }
            return;
        }
        if (speichern.fertig(auftrag, id, zip, anzahl)) {
            LOG.infof("Mandanten-Vollexport %s fertig: dokumente=%d groesse=%d", id, anzahl, zip.groesse());
        } else {
            LOG.infof("Mandanten-Vollexport %s wurde bereits abgeschlossen", id);
        }
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        speichern.fehler(auftrag.nutzlast().uuid(MandantVollexport.EXPORT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne Export-ID")), fehlerId);
        LOG.errorf("Mandanten-Vollexport fehlgeschlagen (fehler-id=%s)", fehlerId);
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, AuftragFehler fehler) {
        nachLetztemFehlversuch(auftrag, fehler.fehlerId());
    }
}
