package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig.Auswirkung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
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

    public EinstellungsAenderungsPruefung(EinstellungsStartwerte startwerte,
                                           EinstellungsAuswirkungBerechnen auswirkung, StepUp stepUp,
                                           MandantAufloesung aufloesung, MandantKontext kontext) {
        this.startwerte = startwerte;
        this.auswirkung = auswirkung;
        this.stepUp = stepUp;
        this.aufloesung = aufloesung;
        this.kontext = kontext;
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

}
