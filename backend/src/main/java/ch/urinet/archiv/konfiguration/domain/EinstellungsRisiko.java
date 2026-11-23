package ch.urinet.archiv.konfiguration.domain;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class EinstellungsRisiko {

    public record Startwerte(Period chatAufbewahrung, Integer webseitenChatAufbewahrung, Long wissenMonatsbudget) {
    }

    private EinstellungsRisiko() {
    }

    public static Set<String> geaendert(Konfiguration.Einstellungen bisher, Konfiguration.Einstellungen neu,
                                         Startwerte startwerte, String mandant) {
        return geaendert(bisher, neu, startwerte, mandant, Instant.now());
    }

    public static Set<String> geaendert(Konfiguration.Einstellungen bisher, Konfiguration.Einstellungen neu,
                                         Startwerte startwerte, String mandant, Instant jetzt) {
        Objects.requireNonNull(bisher);
        Objects.requireNonNull(neu);
        Objects.requireNonNull(startwerte);
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(jetzt);

        Set<String> riskant = new LinkedHashSet<>();
        if (!Objects.requireNonNullElse(bisher.geschaeftsjahrEnde(), Einstellungskatalog.GESCHAEFTSJAHR_STANDARD)
                .equals(Objects.requireNonNullElse(neu.geschaeftsjahrEnde(),
                        Einstellungskatalog.GESCHAEFTSJAHR_STANDARD))) {
            riskant.add("geschaeftsjahrEnde");
        }
        Period standard = startwerte.chatAufbewahrung() == null
                ? Period.ofMonths(Einstellungskatalog.Zahl.CHAT_AUFBEWAHRUNG.standardFuer(mandant))
                : startwerte.chatAufbewahrung();
        Period alteDauer = bisher.chat().aufbewahrungMonate() == null
                ? standard : Period.ofMonths(bisher.chat().aufbewahrungMonate());
        Period neueDauer = neu.chat().aufbewahrungMonate() == null
                ? standard : Period.ofMonths(neu.chat().aufbewahrungMonate());
        if (jetzt.atOffset(ZoneOffset.UTC).minus(neueDauer)
                .isAfter(jetzt.atOffset(ZoneOffset.UTC).minus(alteDauer))) {
            riskant.add(Einstellungskatalog.Zahl.CHAT_AUFBEWAHRUNG.schluessel());
        }
        kuerzer(riskant, Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG,
                bisher.webseitenChat().aufbewahrungTage(), neu.webseitenChat().aufbewahrungTage(),
                startwerte.webseitenChatAufbewahrung(), mandant);

        var budget = Einstellungskatalog.Zahl.WISSEN_MONATSBUDGET;
        long startBudget = startwerte.wissenMonatsbudget() == null
                ? budget.standardFuer(mandant) : startwerte.wissenMonatsbudget();
        long bisherigesBudget = bisher.wissen().monatsbudgetTokens() == null
                ? startBudget : bisher.wissen().monatsbudgetTokens();
        long neuesBudget = neu.wissen().monatsbudgetTokens() == null
                ? startBudget : neu.wissen().monatsbudgetTokens();
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
