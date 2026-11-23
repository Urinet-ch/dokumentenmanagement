package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragenAufraeumen;
import ch.urinet.archiv.konfiguration.domain.EinstellungsRisiko;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenEinstellungenRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Period;

@ApplicationScoped
public class EinstellungsStartwerte {
    private final MandantAufloesung aufloesung;
    private final MandantKontext kontext;
    private final OeffentlicheFragenAufraeumen oeffentlicheFragen;
    private final WissenEinstellungenRepository wissen;
    private final Period chatAufbewahrung;
    private final long wissenMonatsbudget;

    public EinstellungsStartwerte(MandantAufloesung aufloesung, MandantKontext kontext,
                                  OeffentlicheFragenAufraeumen oeffentlicheFragen,
                                  WissenEinstellungenRepository wissen,
                                  @ConfigProperty(name = "archiv.chat.aufbewahrung") String chatAufbewahrung,
                                  @ConfigProperty(name = "archiv.wissen.monatsbudget-tokens") long wissenMonatsbudget) {
        this.aufloesung = aufloesung;
        this.kontext = kontext;
        this.oeffentlicheFragen = oeffentlicheFragen;
        this.wissen = wissen;
        this.chatAufbewahrung = Period.parse(chatAufbewahrung);
        if (this.chatAufbewahrung.isNegative() || this.chatAufbewahrung.isZero() || wissenMonatsbudget <= 0) {
            throw new IllegalArgumentException("Startwerte der Aufbewahrung oder des Wissensbudgets sind ungültig");
        }
        this.wissenMonatsbudget = wissenMonatsbudget;
    }

    public EinstellungsRisiko.Startwerte lesen() {
        MandantCode mandant = kontext.aktuell().orElseGet(aufloesung::pruefen);
        aufloesung.schema();
        Integer eigenesWissensbudget = wissen.lesen().monatsbudgetTokens();
        long startBudget = eigenesWissensbudget == null ? wissenMonatsbudget : eigenesWissensbudget.longValue();
        return new EinstellungsRisiko.Startwerte(chatAufbewahrung, oeffentlicheFragen.tage(mandant), startBudget);
    }
}
