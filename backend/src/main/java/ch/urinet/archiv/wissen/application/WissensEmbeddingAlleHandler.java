package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WissensEmbeddingAlleHandler implements AuftragHandler {
    private final WissensEmbeddingSpeichern speichern;

    public WissensEmbeddingAlleHandler(WissensEmbeddingSpeichern speichern) { this.speichern = speichern; }

    @Override
    public AuftragTyp typ() { return WissensEmbeddingSpeichern.ALLE; }

    @Override
    public void ausfuehren(Auftrag auftrag) { speichern.teilEinreihen(auftrag); }
}
