package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissenIngestGrenzen;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WissenKonfiguration {

    private final String modell;
    private final WissenIngestGrenzen ingest;

    public WissenKonfiguration(@ConfigProperty(name = "archiv.wissen.modell") String modell,
                               @ConfigProperty(name = "archiv.wissen.abschnitt-zeichen") int abschnittZeichen,
                               @ConfigProperty(name = "archiv.wissen.max-seiten") int maxSeiten,
                               @ConfigProperty(name = "archiv.wissen.index-max-zeichen") int indexMaxZeichen,
                               @ConfigProperty(name = "archiv.wissen.seite-max-zeichen") int seiteMaxZeichen,
                               @ConfigProperty(name = "archiv.wissen.werkzeug-max-zeichen") int werkzeugMaxZeichen,
                               @ConfigProperty(name = "archiv.wissen.budget-tokens") long budgetTokens,
                               @ConfigProperty(name = "archiv.wissen.monatsbudget-tokens") long monatsbudgetTokens) {
        if (modell == null || modell.isBlank()) {
            throw new IllegalStateException("archiv.wissen.modell muss gesetzt sein");
        }
        this.modell = modell.strip();
        this.ingest = new WissenIngestGrenzen(abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen,
                werkzeugMaxZeichen, budgetTokens, monatsbudgetTokens);
    }

    public String modell() {
        return modell;
    }

    public WissenIngestGrenzen ingest() {
        return ingest;
    }
}
