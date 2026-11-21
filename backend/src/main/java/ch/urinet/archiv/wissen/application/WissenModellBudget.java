package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@ApplicationScoped
public class WissenModellBudget {
    private final WissenIngestPort port;
    private final WissenKonfiguration konfiguration;
    private final boolean embeddingStub;
    private final boolean rerankerStub;

    public WissenModellBudget(WissenIngestPort port, WissenKonfiguration konfiguration,
                             @ConfigProperty(name = "archiv.ki.embedding-stub") boolean embeddingStub,
                             @ConfigProperty(name = "archiv.ki.reranker-stub") boolean rerankerStub) {
        this.port = port;
        this.konfiguration = konfiguration;
        this.embeddingStub = embeddingStub;
        this.rerankerStub = rerankerStub;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void embeddingPruefen() {
        if (!embeddingStub) pruefen();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void rerankerPruefen() {
        if (!rerankerStub) pruefen();
    }

    private void pruefen() {
        var einstellungen = port.budgetEinstellungenLaden();
        long grenze = einstellungen.monatsbudgetTokens() == null
                ? konfiguration.ingest().monatsbudgetTokens() : einstellungen.monatsbudgetTokens();
        var monat = WissenIngestStarten.monat(Instant.now());
        if (port.verbrauchImZeitraum(monat.von(), monat.bis()) >= grenze) {
            throw new FachFehler(Fehlercode.BUDGET);
        }
    }
}
