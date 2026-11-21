package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class WissenExportHandler implements AuftragHandler {
    public static final AuftragTyp TYP = new AuftragTyp("WISSEN_EXPORT");
    public static final String EXPORT_ID = "exportId";
    private static final Logger LOG = Logger.getLogger(WissenExportHandler.class);

    private final WissenExportSpeichern speichern;
    private final WissenOkfExportPort schreiber;
    private final AblagePort ablage;

    public WissenExportHandler(WissenExportSpeichern speichern, WissenOkfExportPort schreiber, AblagePort ablage) {
        this.speichern = speichern;
        this.schreiber = schreiber;
        this.ablage = ablage;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(EXPORT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne Export-ID"));
        WissenExportRepository.Export export = speichern.laden(id)
                .orElseThrow(() -> new IllegalStateException("Wissensexport fehlt"));
        if (export.status() != WissenExportRepository.Status.OFFEN) {
            LOG.infof("Wissensexport %s schon %s", id, export.status());
            return;
        }
        ablage.exportLebenszyklusSichern(auftrag.mandant());
        AbgelegteDatei zip;
        try (Exportziel ziel = ablage.wissensExportSchreiben(auftrag.mandant(), id)) {
            schreiber.schreiben(auftrag.mandant(), ziel.strom());
            zip = ziel.abschliessen();
        }
        if (speichern.fertig(auftrag, export, zip)) {
            LOG.infof("Wissensexport %s abgeschlossen", id);
        } else {
            LOG.infof("Wissensexport %s bereits abgeschlossen", id);
        }
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        UUID id = auftrag.nutzlast().uuid(EXPORT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne Export-ID"));
        if (speichern.fehler(id, fehlerId)) {
            LOG.errorf("Wissensexport %s fehlgeschlagen (fehler-id=%s)", id, fehlerId);
        }
    }
}
