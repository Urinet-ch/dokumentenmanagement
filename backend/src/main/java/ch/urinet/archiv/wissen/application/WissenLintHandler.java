package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class WissenLintHandler implements AuftragHandler {
    private static final Logger LOG = Logger.getLogger(WissenLintHandler.class);
    private final WissenLintVerarbeiten verarbeiten;
    private final WissenLintSpeichern speichern;

    public WissenLintHandler(WissenLintVerarbeiten verarbeiten, WissenLintSpeichern speichern) {
        this.verarbeiten = verarbeiten;
        this.speichern = speichern;
    }

    @Override
    public AuftragTyp typ() {
        return WissenLintSpeichern.TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) {
        UUID id = auftrag.nutzlast().uuid(WissenLintSpeichern.LINT_ID)
                .orElseThrow(() -> new IllegalArgumentException("Auftragsnutzlast ohne lintId"));
        verarbeiten.ausfuehren(auftrag, id);
    }

    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        auftrag.nutzlast().uuid(WissenLintSpeichern.LINT_ID).ifPresent(id -> {
            speichern.nachFehlversuch(auftrag, id);
            LOG.warnf("Wissens-Lint %s nach letztem Fehlversuch beendet (fehler-id=%s)", id, fehlerId);
        });
    }
}
