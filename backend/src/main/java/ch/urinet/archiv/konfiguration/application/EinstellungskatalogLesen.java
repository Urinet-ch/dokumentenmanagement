package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.ki.application.oeffentlich.MandantBudget;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog.Einstellung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.mandant.application.BrandingVerzeichnis;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.domain.Branding;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenEinstellungenRepository;
import ch.urinet.archiv.wissen.application.WissenKonfiguration;
import ch.urinet.archiv.wissen.application.WissenLintStarten;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.DayOfWeek;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class EinstellungskatalogLesen {
    private static final Pattern WOCHENPLAN = Pattern.compile("^0 0 (\\d{1,2}) \\? \\* (MON|TUE|WED|THU|FRI|SAT|SUN)$");

    public record Eintrag(String schluessel, Einstellungskatalog.Gruppe gruppe, String name, String beschreibung,
                          Einstellungskatalog.Typ typ, String einheit, Integer minimum, Integer maximum,
                          String formregel, Object standard, Object aktuellerWert, boolean eigenerWert,
                          boolean riskant, String wirksamAb, String hinweis) {
    }

    public record Betrieb(MandantBudget.Stand oeffentlicherChat, long wissenMonatsbudgetTokens) {
    }

    public record Ergebnis(int version, List<Eintrag> katalog, Betrieb betrieb) {
        public Ergebnis {
            katalog = List.copyOf(katalog);
        }
    }

    private final KonfigurationVerwaltung konfiguration;
    private final MandantAufloesung aufloesung;
    private final MandantBudget budget;
    private final EinstellungsStartwerte startwerte;
    private final BrandingVerzeichnis brandings;
    private final WissenEinstellungenRepository wissen;
    private final WissenKonfiguration wissenKonfiguration;
    private final WissenLintStarten lint;
    private final long lintBudget;
    private final String lintStandardZeitplan;

    public EinstellungskatalogLesen(KonfigurationVerwaltung konfiguration, MandantAufloesung aufloesung,
                                    MandantBudget budget, EinstellungsStartwerte startwerte,
                                    BrandingVerzeichnis brandings, WissenEinstellungenRepository wissen,
                                    WissenKonfiguration wissenKonfiguration, WissenLintStarten lint,
                                    @ConfigProperty(name = "archiv.wissen.lint-budget-tokens", defaultValue = "100000")
                                    long lintBudget,
                                    @ConfigProperty(name = "archiv.wissen.lint-zeitplan",
                                            defaultValue = "0 0 4 ? * SUN") String lintStandardZeitplan) {
        this.konfiguration = konfiguration;
        this.aufloesung = aufloesung;
        this.budget = budget;
        this.startwerte = startwerte;
        this.brandings = brandings;
        this.wissen = wissen;
        this.wissenKonfiguration = wissenKonfiguration;
        this.lint = lint;
        this.lintBudget = lintBudget;
        this.lintStandardZeitplan = lintStandardZeitplan;
    }

    @Transactional
    public Ergebnis lesen(Rolle rolle) {
        MandantCode mandant = aufloesung.pruefen();
        var aktuell = konfiguration.aktuell();
        var start = startwerte.lesen();
        var wissenswerte = wissen.lesen();
        Branding branding = brandings.fuerMandant(mandant).orElse(Branding.STANDARD);
        String zeitplan = lint.zeitplan();
        List<Eintrag> eintraege = Einstellungskatalog.alle().stream()
                .filter(e -> Rechtematrix.ergebnis(rolle,
                        e.recht() == Einstellungskatalog.Recht.A19 ? Aktion.A19 : Aktion.A20).zugaenglich())
                .map(e -> eintrag(e, aktuell.einstellungen(), mandant, branding, start, wissenswerte, zeitplan))
                .toList();
        return new Ergebnis(aktuell.version(), eintraege,
                new Betrieb(budget.stand(mandant), wissenswerte.monatsbudgetTokens() == null
                        ? start.wissenMonatsbudget() : wissenswerte.monatsbudgetTokens()));
    }

    private Eintrag eintrag(Einstellung einstellung, Einstellungen werte, MandantCode mandant, Branding branding,
                            ch.urinet.archiv.konfiguration.domain.EinstellungsRisiko.Startwerte start,
                            ch.urinet.archiv.wissen.application.WissenEinstellungen.Werte wissenswerte,
                            String zeitplan) {
        String schluessel = einstellung.schluessel();
        boolean wissenEinstellung = schluessel.startsWith("wissen.");
        Object eigenerWert = wissenEinstellung ? eigenerWissenswert(schluessel, wissenswerte)
                : eigenerWert(schluessel, werte);
        boolean eigenerWissenszeitplan = schluessel.startsWith("wissen.lintZeitplan.")
                && wissenswerte.lintZeitplan() != null;
        Object standard = switch (schluessel) {
            case "branding.name" -> branding.name();
            case "branding.primaerfarbe" -> branding.primaerfarbe();
            case "chat.aufbewahrungMonate" -> monateOderPeriode(start.chatAufbewahrung());
            case "webseitenChat.aufbewahrungTage" -> start.webseitenChatAufbewahrung();
            case "wissen.budgetTokens" -> wissenKonfiguration.ingest().budgetTokens();
            case "wissen.lintBudgetTokens" -> lintBudget;
            case "wissen.monatsbudgetTokens" -> wissenKonfiguration.ingest().monatsbudgetTokens();
            case "wissen.lintZeitplan.wochentag" -> tag(lintStandardZeitplan);
            case "wissen.lintZeitplan.stunde" -> stunde(lintStandardZeitplan);
            default -> einstellung.standardFuer(mandant.wert());
        };
        String hinweis = schluessel.startsWith("wissen.lintZeitplan.")
                && (eigenerWissenszeitplan ? eigenerWert == null : standard == null)
                ? "Der bisherige Zeitplan lässt sich nicht als einzelner Wochentag und Stunde darstellen: " + zeitplan
                : schluessel.equals("chat.aufbewahrungMonate") && standard instanceof String
                ? "Die bisherige Aufbewahrung ist eine ISO-8601-Periode und keine ganze Monatszahl." : null;
        var zahl = einstellung.zahl();
        return new Eintrag(schluessel, einstellung.gruppe(), einstellung.name(), einstellung.beschreibung(),
                einstellung.typ(), einstellung.einheit(), zahl == null ? null : zahl.minimum(),
                zahl == null ? null : zahl.maximum(), formregel(einstellung), standard,
                eigenerWissenszeitplan ? eigenerWert : eigenerWert == null ? standard : eigenerWert,
                eigenerWert != null || eigenerWissenszeitplan,
                einstellung.risiko() != Einstellungskatalog.Risiko.KEINES, einstellung.wirksamAb(), hinweis);
    }

    private static Object eigenerWissenswert(String schluessel,
                                               ch.urinet.archiv.wissen.application.WissenEinstellungen.Werte werte) {
        return switch (schluessel) {
            case "wissen.budgetTokens" -> werte.budgetTokens();
            case "wissen.lintBudgetTokens" -> werte.lintBudgetTokens();
            case "wissen.monatsbudgetTokens" -> werte.monatsbudgetTokens();
            case "wissen.lintZeitplan.wochentag" -> werte.lintZeitplan() == null ? null : tag(werte.lintZeitplan());
            case "wissen.lintZeitplan.stunde" -> werte.lintZeitplan() == null ? null
                    : stunde(werte.lintZeitplan());
            default -> throw new IllegalStateException("Wissenseinstellung ohne Wert");
        };
    }

    private static Object eigenerWert(String schluessel, Einstellungen werte) {
        return switch (schluessel) {
            case "geschaeftsjahrEnde" -> werte.geschaeftsjahrEnde();
            case "kennungen.uid" -> werte.kennungen().uid();
            case "kennungen.ibans" -> werte.kennungen().ibans().isEmpty() ? null : werte.kennungen().ibans();
            case "branding.name" -> werte.branding().name();
            case "branding.primaerfarbe" -> werte.branding().primaerfarbe();
            case "chat.aufbewahrungMonate" -> werte.chat().aufbewahrungMonate();
            case "webseitenChat.aufbewahrungTage" -> werte.webseitenChat().aufbewahrungTage();
            default -> throw new IllegalStateException("Katalogeintrag ohne Wert");
        };
    }

    private static String formregel(Einstellung einstellung) {
        return switch (einstellung.typ()) {
            case DATUM -> "Gültiger Tag im Format MM-TT.";
            case UID -> "Schweizer UID im Format CHE-123.456.789, optional mit MWST.";
            case IBANS -> "Höchstens 20 Einträge mit je höchstens 34 Zeichen, ohne Steuerzeichen.";
            case TEXT -> "1 bis 60 Zeichen, ohne Steuerzeichen oder Markup.";
            case FARBE -> "RGB-Farbe #RRGGBB mit Kontrast mindestens 4.5.";
            case WOCHENTAG -> "MONDAY bis SUNDAY.";
            case GANZZAHL -> null;
        };
    }

    private static Object monateOderPeriode(Period periode) {
        return periode.getDays() == 0 ? periode.toTotalMonths() : periode.toString();
    }

    private static DayOfWeek tag(String zeitplan) {
        Matcher treffer = WOCHENPLAN.matcher(zeitplan.toUpperCase(Locale.ROOT));
        if (!treffer.matches()) {
            return null;
        }
        return switch (treffer.group(2)) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalStateException("Zeitplan ohne Wochentag");
        };
    }

    private static Integer stunde(String zeitplan) {
        Matcher treffer = WOCHENPLAN.matcher(zeitplan.toUpperCase(Locale.ROOT));
        return treffer.matches() ? Integer.valueOf(treffer.group(1)) : null;
    }
}
