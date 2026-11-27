package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissenIngestGrenzen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WissenKonfiguration {
    private static final int MAX_GRUPPEN_PRO_TABELLE = 40;

    private final String modell;
    private final WissenIngestGrenzen ingest;
    private final boolean tabellenGruppierung;
    private final int tabellenMaxGruppen;

    @Inject
    public WissenKonfiguration(@ConfigProperty(name = "archiv.wissen.modell") String modell,
                               @ConfigProperty(name = "archiv.wissen.abschnitt-zeichen") int abschnittZeichen,
                               @ConfigProperty(name = "archiv.wissen.max-seiten") int maxSeiten,
                               @ConfigProperty(name = "archiv.wissen.index-max-zeichen") int indexMaxZeichen,
                               @ConfigProperty(name = "archiv.wissen.seite-max-zeichen") int seiteMaxZeichen,
                               @ConfigProperty(name = "archiv.wissen.werkzeug-max-zeichen") int werkzeugMaxZeichen,
                               @ConfigProperty(name = "archiv.wissen.budget-tokens") long budgetTokens,
                               @ConfigProperty(name = "archiv.wissen.monatsbudget-tokens") long monatsbudgetTokens,
                               @ConfigProperty(name = "archiv.wissen.auszug-mindest-zeichen") int auszugMindestZeichen,
                               @ConfigProperty(name = "archiv.wissen.aussage-ueberlappung") double aussageUeberlappung,
                               @ConfigProperty(name = "archiv.wissen.randzeilen-mindest-anteil")
                               double randzeilenMindestAnteil,
                               @ConfigProperty(name = "archiv.wissen.tabellen-gruppierung") boolean tabellenGruppierung,
                               @ConfigProperty(name = "archiv.wissen.tabellen-max-gruppen") int tabellenMaxGruppen) {
        if (modell == null || modell.isBlank()) {
            throw new IllegalStateException("archiv.wissen.modell muss gesetzt sein");
        }
        if (tabellenMaxGruppen < 1 || tabellenMaxGruppen > MAX_GRUPPEN_PRO_TABELLE) {
            throw new IllegalStateException("archiv.wissen.tabellen-max-gruppen muss zwischen 1 und "
                    + MAX_GRUPPEN_PRO_TABELLE + " liegen");
        }
        this.modell = modell.strip();
        this.ingest = new WissenIngestGrenzen(abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen,
                werkzeugMaxZeichen, budgetTokens, monatsbudgetTokens, auszugMindestZeichen, aussageUeberlappung,
                randzeilenMindestAnteil);
        this.tabellenGruppierung = tabellenGruppierung;
        this.tabellenMaxGruppen = tabellenMaxGruppen;
    }

    public WissenKonfiguration(String modell, int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                               int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                               long monatsbudgetTokens) {
        this(modell, abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen, werkzeugMaxZeichen,
                budgetTokens, monatsbudgetTokens, 20, 0.5, 0.3);
    }

    public WissenKonfiguration(String modell, int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                               int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                               long monatsbudgetTokens, int auszugMindestZeichen, double aussageUeberlappung) {
        this(modell, abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen, werkzeugMaxZeichen,
                budgetTokens, monatsbudgetTokens, auszugMindestZeichen, aussageUeberlappung, 0.3);
    }

    public WissenKonfiguration(String modell, int abschnittZeichen, int maxSeiten, int indexMaxZeichen,
                               int seiteMaxZeichen, int werkzeugMaxZeichen, long budgetTokens,
                               long monatsbudgetTokens, int auszugMindestZeichen, double aussageUeberlappung,
                               double randzeilenMindestAnteil) {
        this(modell, abschnittZeichen, maxSeiten, indexMaxZeichen, seiteMaxZeichen, werkzeugMaxZeichen,
                budgetTokens, monatsbudgetTokens, auszugMindestZeichen, aussageUeberlappung,
                randzeilenMindestAnteil, true, 1);
    }

    public String modell() {
        return modell;
    }

    public WissenIngestGrenzen ingest() {
        return ingest;
    }

    public boolean tabellenGruppierung() {
        return tabellenGruppierung;
    }

    public int tabellenMaxGruppen() {
        return tabellenMaxGruppen;
    }
}
