package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class WissenIngestHandler implements AuftragHandler {

    private static final Logger LOG = Logger.getLogger(WissenIngestHandler.class);

    private final WissenIngestVerarbeiten verarbeiten;
    private final WissenIngestSpeichern speichern;

    public WissenIngestHandler(WissenIngestVerarbeiten verarbeiten, WissenIngestSpeichern speichern) {
        this.verarbeiten = verarbeiten;
        this.speichern = speichern;
    }

    @Override
    public AuftragTyp typ() {
        return WissenIngestStarten.TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID entwurfId = auftrag.nutzlast().uuid(WissenIngestStarten.NUTZLAST_ENTWURF_ID)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Auftragsnutzlast ohne " + WissenIngestStarten.NUTZLAST_ENTWURF_ID));
        verarbeiten.ausfuehren(auftrag, entwurfId);
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        auftrag.nutzlast().uuid(WissenIngestStarten.NUTZLAST_ENTWURF_ID).ifPresent(entwurfId -> {
            speichern.nachFehlversuch(entwurfId);
            LOG.warnf("Wissens-Verarbeitung %s nach letztem Fehlversuch beendet (fehler-id=%s)",
                    entwurfId, fehlerId);
        });
    }
}
