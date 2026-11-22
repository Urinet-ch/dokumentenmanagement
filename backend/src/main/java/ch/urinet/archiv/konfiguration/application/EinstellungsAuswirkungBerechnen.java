package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig.Auswirkung;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import ch.urinet.archiv.konfiguration.domain.EinstellungsRisiko;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class EinstellungsAuswirkungBerechnen {
    private final EinstellungsAuswirkungen daten;

    public EinstellungsAuswirkungBerechnen(EinstellungsAuswirkungen daten) {
        this.daten = daten;
    }

    public List<Auswirkung> berechnen(Einstellungen vorher, Einstellungen ziel,
                                      EinstellungsRisiko.Startwerte startwerte, String mandant, Instant jetzt) {
        return EinstellungsRisiko.geaendert(vorher, ziel, startwerte, mandant).stream().sorted()
                .map(schluessel -> auswirkung(schluessel, ziel, startwerte, mandant, jetzt)).toList();
    }

    public void bestaetigungPruefen(List<Auswirkung> auswirkungen, Set<String> bestaetigt) {
        List<Auswirkung> offen = auswirkungen.stream()
                .filter(auswirkung -> !bestaetigt.contains(auswirkung.schluessel())).toList();
        if (!offen.isEmpty()) {
            throw new BestaetigungNoetig(offen);
        }
    }

    private Auswirkung auswirkung(String schluessel, Einstellungen ziel, EinstellungsRisiko.Startwerte startwerte,
                                  String mandant, Instant jetzt) {
        return switch (schluessel) {
            case "geschaeftsjahrEnde" -> new Auswirkung(schluessel,
                    "Gilt für künftig bestätigte Dokumente; archivierte Fristen und Sperren bleiben unverändert.", 0L);
            case "chat.aufbewahrungMonate" -> aufbewahrung(schluessel,
                    Einstellungskatalog.Zahl.CHAT_AUFBEWAHRUNG, ziel.chat().aufbewahrungMonate(),
                    startwerte.chatAufbewahrung(), mandant, jetzt, true);
            case "webseitenChat.aufbewahrungTage" -> aufbewahrung(schluessel,
                    Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG,
                    ziel.webseitenChat().aufbewahrungTage(), startwerte.webseitenChatAufbewahrung(),
                    mandant, jetzt, false);
            case "wissen.monatsbudgetTokens" -> budget(schluessel, ziel.wissen().monatsbudgetTokens(),
                    startwerte.wissenMonatsbudget(), mandant);
            default -> throw new IllegalStateException("Unbekannte riskante Einstellung");
        };
    }

    private Auswirkung aufbewahrung(String schluessel, Einstellungskatalog.Zahl einstellung, Integer wert,
                                    Integer startwert, String mandant, Instant jetzt, boolean monate) {
        if (!einstellung.gueltig(wert)) {
            return ungueltig(schluessel);
        }
        int wirksam = einstellung.wirksamerWert(wert, startwert, mandant);
        Instant grenze = (monate ? jetzt.atOffset(ZoneOffset.UTC).minusMonths(wirksam)
                : jetzt.atOffset(ZoneOffset.UTC).minusDays(wirksam)).toInstant();
        long anzahl = monate ? daten.chatNachrichtenVor(grenze) : daten.oeffentlicheFragenVor(grenze);
        String text = monate
                ? "Nach aktuellem Stand würden beim nächsten Aufräumlauf " + anzahl + " Chatnachrichten gelöscht."
                : "Nach aktuellem Stand würden beim nächsten Aufräumlauf " + anzahl + " öffentliche Fragen gelöscht.";
        return new Auswirkung(schluessel, text, anzahl);
    }

    private static Auswirkung budget(String schluessel, Integer wert, Integer startwert, String mandant) {
        var einstellung = Einstellungskatalog.Zahl.WISSEN_MONATSBUDGET;
        if (!einstellung.gueltig(wert)) {
            return ungueltig(schluessel);
        }
        int tokens = einstellung.wirksamerWert(wert, startwert, mandant);
        BigDecimal kosten = BigDecimal.valueOf(tokens).multiply(new BigDecimal("0.75"))
                .divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.CEILING);
        return new Auswirkung(schluessel, "Richtwert bis CHF " + kosten.toPlainString()
                + " pro Monat (Mistral Small 4, Preisstand 08.11.2026, ohne MWST).", 0L);
    }

    private static Auswirkung ungueltig(String schluessel) {
        return new Auswirkung(schluessel,
                "Die Auswirkung kann erst nach Korrektur des ungültigen Werts berechnet werden.", null);
    }
}
