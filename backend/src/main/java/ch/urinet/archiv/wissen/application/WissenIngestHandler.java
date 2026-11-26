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
    private final ManuelleNeuerzeugung manuell;
    private final ManuelleNeuerzeugungSpeichern manuellSpeichern;

    public WissenIngestHandler(WissenIngestVerarbeiten verarbeiten, WissenIngestSpeichern speichern,
                               ManuelleNeuerzeugung manuell, ManuelleNeuerzeugungSpeichern manuellSpeichern) {
        this.verarbeiten = verarbeiten;
        this.speichern = speichern;
        this.manuell = manuell;
        this.manuellSpeichern = manuellSpeichern;
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
        if (manuellSpeichern.istManuell(entwurfId)) {
            int nummer = Math.toIntExact(auftrag.nutzlast().zahl("aenderungNr")
                    .orElseThrow(() -> new IllegalArgumentException("Auftragsnutzlast ohne aenderungNr")));
            manuell.ausfuehren(auftrag, entwurfId, nummer);
        } else {
            verarbeiten.ausfuehren(auftrag, entwurfId);
        }
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        auftrag.nutzlast().uuid(WissenIngestStarten.NUTZLAST_ENTWURF_ID).ifPresent(entwurfId -> {
            verarbeiten.auszugPrueferVerwerfen(entwurfId);
            if (auftrag.nutzlast().zahl("aenderungNr").isPresent()) {
                manuellSpeichern.nachFehlversuch(auftrag, entwurfId,
                        Math.toIntExact(auftrag.nutzlast().zahl("aenderungNr").orElseThrow()));
            } else {
                speichern.nachFehlversuch(entwurfId);
            }
            LOG.warnf("Wissens-Verarbeitung %s nach letztem Fehlversuch beendet (fehler-id=%s)",
                    entwurfId, fehlerId);
        });
    }
}
