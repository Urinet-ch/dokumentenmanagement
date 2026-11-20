package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class WissenEinstellungen {
    public record Werte(Integer budgetTokens, Integer lintBudgetTokens, String lintZeitplan, Integer monatsbudgetTokens) {
    }

    private final WissenEinstellungenRepository repository;
    private final Berechtigung berechtigung;
    private final WissenZeitplan zeitplan;

    public WissenEinstellungen(WissenEinstellungenRepository repository, Berechtigung berechtigung,
                               WissenZeitplan zeitplan) {
        this.repository = repository;
        this.berechtigung = berechtigung;
        this.zeitplan = zeitplan;
    }

    @Transactional
    public Werte lesen(Benutzer benutzer) {
        berechtigung.aktion(benutzer, Aktion.A19);
        return repository.lesen();
    }

    @Transactional
    public Werte speichern(Benutzer benutzer, Werte werte) {
        berechtigung.aktion(benutzer, Aktion.A19);
        pruefen(werte);
        repository.speichern(werte);
        return werte;
    }

    void pruefen(Werte werte) {
        if (werte == null || ungueltig(werte.budgetTokens()) || ungueltig(werte.lintBudgetTokens())
                || ungueltig(werte.monatsbudgetTokens())) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        if (werte.lintZeitplan() != null && !zeitplan.gueltig(werte.lintZeitplan())) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
    }

    private static boolean ungueltig(Integer wert) {
        return wert != null && wert <= 0;
    }
}
