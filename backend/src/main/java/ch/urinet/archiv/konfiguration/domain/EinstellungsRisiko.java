package ch.urinet.archiv.konfiguration.domain;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class EinstellungsRisiko {

    public record Startwerte(Integer chatAufbewahrung, Integer webseitenChatAufbewahrung,
                             Integer wissenMonatsbudget) {
    }

    private EinstellungsRisiko() {
    }

    public static Set<String> geaendert(Konfiguration.Einstellungen bisher, Konfiguration.Einstellungen neu,
                                         Startwerte startwerte, String mandant) {
        Objects.requireNonNull(bisher);
        Objects.requireNonNull(neu);
        Objects.requireNonNull(startwerte);
        Objects.requireNonNull(mandant);

        Set<String> riskant = new LinkedHashSet<>();
        if (!Objects.requireNonNullElse(bisher.geschaeftsjahrEnde(), Einstellungskatalog.GESCHAEFTSJAHR_STANDARD)
                .equals(Objects.requireNonNullElse(neu.geschaeftsjahrEnde(),
                        Einstellungskatalog.GESCHAEFTSJAHR_STANDARD))) {
            riskant.add("geschaeftsjahrEnde");
        }
        kuerzer(riskant, Einstellungskatalog.Zahl.CHAT_AUFBEWAHRUNG,
                bisher.chat().aufbewahrungMonate(), neu.chat().aufbewahrungMonate(),
                startwerte.chatAufbewahrung(), mandant);
        kuerzer(riskant, Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG,
                bisher.webseitenChat().aufbewahrungTage(), neu.webseitenChat().aufbewahrungTage(),
                startwerte.webseitenChatAufbewahrung(), mandant);

        var budget = Einstellungskatalog.Zahl.WISSEN_MONATSBUDGET;
        int bisherigesBudget = budget.wirksamerWert(bisher.wissen().monatsbudgetTokens(),
                startwerte.wissenMonatsbudget(), mandant);
        int neuesBudget = vorgeschlagenerWert(budget, neu.wissen().monatsbudgetTokens(),
                startwerte.wissenMonatsbudget(), mandant);
        if (neuesBudget != bisherigesBudget && neuesBudget > 2L * budget.standardFuer(mandant)) {
            riskant.add(budget.schluessel());
        }
        return Set.copyOf(riskant);
    }

    private static void kuerzer(Set<String> riskant, Einstellungskatalog.Zahl einstellung, Integer bisher,
                                Integer neu, Integer startwert, String mandant) {
        if (vorgeschlagenerWert(einstellung, neu, startwert, mandant)
                < einstellung.wirksamerWert(bisher, startwert, mandant)) {
            riskant.add(einstellung.schluessel());
        }
    }

    private static int vorgeschlagenerWert(Einstellungskatalog.Zahl einstellung, Integer wert,
                                          Integer startwert, String mandant) {
        return wert != null ? wert : einstellung.wirksamerWert(null, startwert, mandant);
    }
}
