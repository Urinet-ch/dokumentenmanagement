package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.konfiguration.application.BestaetigteEinstellungen;
import ch.urinet.archiv.konfiguration.application.EinstellungenVerwaltung;
import ch.urinet.archiv.konfiguration.application.WissenEinstellungenVerwaltung;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

@ApplicationScoped
public class WissenEinstellungen {
    public record Werte(Integer budgetTokens, Integer lintBudgetTokens, String lintZeitplan, Integer monatsbudgetTokens) {
    }

    private final Berechtigung berechtigung;
    private final WissenEinstellungenVerwaltung verwaltung;

    public WissenEinstellungen(Berechtigung berechtigung, WissenEinstellungenVerwaltung verwaltung) {
        this.berechtigung = berechtigung;
        this.verwaltung = verwaltung;
    }

    @Transactional
    public WissenEinstellungenVerwaltung.Stand lesen(Benutzer benutzer) {
        berechtigung.aktion(benutzer, Aktion.A19);
        return verwaltung.lesen();
    }

    @Transactional
    public WissenEinstellungenVerwaltung.Stand lesen(Benutzer benutzer, int version) {
        berechtigung.aktion(benutzer, Aktion.A19);
        return verwaltung.lesen(version);
    }

    @Transactional
    public EinstellungenVerwaltung.Ergebnis speichern(Benutzer benutzer, int version,
            Supplier<WissenEinstellungenVerwaltung.Eingabe> werte, BestaetigteEinstellungen bestaetigt) {
        berechtigung.aktion(benutzer, Aktion.A19);
        return verwaltung.aendern(version, werte, bestaetigt, benutzer.id());
    }

}
