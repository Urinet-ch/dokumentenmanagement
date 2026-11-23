package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Wissenszeitplan;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@ApplicationScoped
public class WissenEinstellungenVerwaltung {
    public record Stand(int version, WissenEinstellungen.Werte werte) {
    }

    public record Eingabe(WissenEinstellungen.Werte werte, List<KonfigurationUngueltig.Befund> befunde) {
        public Eingabe {
            befunde = List.copyOf(befunde);
        }
    }

    private final KonfigurationVerwaltung konfiguration;
    private final EinstellungenVerwaltung einstellungen;
    private final String standardZeitplan;

    public WissenEinstellungenVerwaltung(KonfigurationVerwaltung konfiguration,
                                          EinstellungenVerwaltung einstellungen,
                                          @ConfigProperty(name = "archiv.wissen.lint-zeitplan",
                                                  defaultValue = "0 0 4 ? * SUN") String standardZeitplan) {
        this.konfiguration = konfiguration;
        this.einstellungen = einstellungen;
        this.standardZeitplan = standardZeitplan;
    }

    public Stand lesen() {
        return stand(konfiguration.aktuell());
    }

    public Stand lesen(int version) {
        return stand(konfiguration.version(version).inhalt());
    }

    private Stand stand(Konfiguration aktuell) {
        var wissen = aktuell.einstellungen().wissen();
        return new Stand(aktuell.version(), new WissenEinstellungen.Werte(wissen.budgetTokens(),
                wissen.lintBudgetTokens(), Wissenszeitplan.cron(wissen.lintZeitplan(), standardZeitplan),
                wissen.monatsbudgetTokens()));
    }

    public EinstellungenVerwaltung.Ergebnis aendern(int version, Supplier<Eingabe> eingabe,
                                                    BestaetigteEinstellungen bestaetigt, String akteur) {
        return einstellungen.aendern(version, vorher -> ziel(vorher, eingabe.get()), bestaetigt, akteur);
    }

    private EinstellungenVerwaltung.Eingabe ziel(Konfiguration.Einstellungen vorher, Eingabe eingabe) {
        var werte = eingabe.werte();
        var befunde = new ArrayList<>(eingabe.befunde());
        Konfiguration.LintZeitplan plan = null;
        if (werte.lintZeitplan() != null) {
            var gelesen = Wissenszeitplan.lesen(werte.lintZeitplan());
            if (gelesen.isEmpty()) {
                befunde.add(new KonfigurationUngueltig.Befund("einstellungen.wissen.lintZeitplan",
                        "WERT_UNGUELTIG", "Der Zeitplan muss einen einzelnen Wochentag und eine Stunde angeben."));
            } else {
                plan = gelesen.get();
            }
        }
        if (Objects.equals(werte.lintZeitplan(), Wissenszeitplan.cron(vorher.wissen().lintZeitplan(), standardZeitplan))) {
            plan = vorher.wissen().lintZeitplan();
        }
        var wissen = new Konfiguration.WissenEinstellungen(werte.budgetTokens(), werte.lintBudgetTokens(),
                werte.monatsbudgetTokens(), plan);
        var ziel = new Konfiguration.Einstellungen(vorher.geschaeftsjahrEnde(), vorher.kennungen(), vorher.branding(),
                vorher.chat(), vorher.webseitenChat(), wissen);
        return new EinstellungenVerwaltung.Eingabe(ziel, befunde);
    }
}
