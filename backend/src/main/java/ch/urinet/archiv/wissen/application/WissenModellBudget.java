package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;

@ApplicationScoped
public class WissenModellBudget {
    private final WissenIngestPort port;
    private final WissenKonfiguration konfiguration;

    public WissenModellBudget(WissenIngestPort port, WissenKonfiguration konfiguration) {
        this.port = port;
        this.konfiguration = konfiguration;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void pruefen() {
        var einstellungen = port.budgetEinstellungenLaden();
        long grenze = einstellungen.monatsbudgetTokens() == null
                ? konfiguration.ingest().monatsbudgetTokens() : einstellungen.monatsbudgetTokens();
        var monat = WissenIngestStarten.monat(Instant.now());
        if (port.verbrauchImZeitraum(monat.von(), monat.bis()) >= grenze) {
            throw new FachFehler(Fehlercode.BUDGET);
        }
    }
}
