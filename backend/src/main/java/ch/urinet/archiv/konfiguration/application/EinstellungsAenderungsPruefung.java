package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig.Auswirkung;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.wissen.application.WissenEinstellungenRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class EinstellungsAenderungsPruefung {
    public enum Ursprung { APP, BETREIBERKOMMANDO }

    private final EinstellungsStartwerte startwerte;
    private final EinstellungsAuswirkungBerechnen auswirkung;
    private final StepUp stepUp;
    private final MandantAufloesung aufloesung;
    private final MandantKontext kontext;
    private final WissenEinstellungenRepository wissen;

    public EinstellungsAenderungsPruefung(EinstellungsStartwerte startwerte,
                                           EinstellungsAuswirkungBerechnen auswirkung, StepUp stepUp,
                                           MandantAufloesung aufloesung, MandantKontext kontext,
                                           WissenEinstellungenRepository wissen) {
        this.startwerte = startwerte;
        this.auswirkung = auswirkung;
        this.stepUp = stepUp;
        this.aufloesung = aufloesung;
        this.kontext = kontext;
        this.wissen = wissen;
    }

    public List<Auswirkung> folgen(Einstellungen vorher, Einstellungen ziel) {
        String mandant = kontext.aktuell().orElseGet(aufloesung::pruefen).wert();
        return auswirkung.berechnen(vorher, ziel, startwerte.lesen(), mandant, Instant.now());
    }

    public void bestaetigt(List<Auswirkung> folgen, Set<String> bestaetigt, Ursprung ursprung) {
        Objects.requireNonNull(ursprung);
        auswirkung.bestaetigungPruefen(folgen, bestaetigt);
        if (ursprung == Ursprung.APP && !folgen.isEmpty()) {
            stepUp.pruefen();
        }
    }

    public void wissenPruefen(Einstellungen vorher, Einstellungen ziel, boolean explizit, boolean vollstaendigerStand) {
        var bisher = vollstaendigerStand ? wissen.lesen() : null;
        boolean eigenerLiveWert = bisher != null && (bisher.budgetTokens() != null || bisher.lintBudgetTokens() != null
                || bisher.monatsbudgetTokens() != null || bisher.lintZeitplan() != null);
        var zielWissen = ziel.wissen();
        boolean eigenerVersionswert = vollstaendigerStand && (zielWissen.budgetTokens() != null
                || zielWissen.lintBudgetTokens() != null || zielWissen.monatsbudgetTokens() != null
                || zielWissen.lintZeitplan().wochentag() != null || zielWissen.lintZeitplan().stunde() != null);
        if (explizit || eigenerLiveWert || eigenerVersionswert || !vorher.wissen().equals(zielWissen)) {
            throw new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund("einstellungen.wissen",
                    "WISSEN_NOCH_NICHT_UEBERFUEHRT",
                    "Ändern Sie die Wissenseinstellungen bis zur Überführung über /wissensbasis/einstellungen.")));
        }
    }
}
