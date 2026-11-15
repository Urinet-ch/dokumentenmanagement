package ch.urinet.archiv.ki.adapter.out.chat;

import ch.urinet.archiv.ki.application.chat.Lueckenbericht;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.domain.chat.Monatsluecken;
import ch.urinet.archiv.ki.domain.chat.Nummernfolge;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Übergang, wird durch Spec 05, Aufgabe 17.6 ersetzt: Der Lückenbericht aus drei Regeln je Mandant als Konfiguration
 * (Entscheid Fabian 14.11.2026, E2), solange es die Regeltabellen von Spec 05 nicht gibt. Schlüssel je Mandant unter
 * {@code archiv.mandant.<code>.luecken.}, mehrere Einträge mit Komma (bei {@code folge} mit Strichpunkt, weil Muster Kommas enthalten), jeder {@code ART:Wert}:
 * <ul>
 *   <li>{@code folge}: {@code FOLGE} nach dem Belegnummer-Muster der Art (Wert = Muster mit der Gruppe {@code nr} und
 *       optional {@code jahr}, je Geschäftsjahr)</li>
 *   <li>{@code erwartet-monatlich}: {@code ERWARTET} je Monat, Wert = Bezug {@code BELEGDATUM} oder
 *       {@code zusatz.<feld>} (Datum)</li>
 *   <li>{@code gruppe} (zu {@code erwartet-monatlich}): getrennte Reihen je {@code PARTNER} oder je {@code zusatz.<feld>};
 *       ohne Eintrag eine Reihe für die ganze Art</li>
 *   <li>{@code nur-monat} (zu {@code erwartet-monatlich}): nur Dokumente, deren {@code zusatz.<feld>} das Format
 *       {@code JJJJ-MM} hat (Monatsabrechnungen, nicht der Jahresausweis)</li>
 * </ul>
 * Die Arten stehen nur in der Konfiguration, nie im Code (Spec 05, Req 2.9). Ein ungültiger Eintrag bricht ab (Meldung
 * ohne Wert).
 */
@ApplicationScoped
public class KonfigurierteLuecken implements Lueckenbericht {

    private static final Pattern ART = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern FELD = Pattern.compile("^zusatz\\.[a-z][a-z0-9_]{0,62}$");
    private static final ZoneId ZONE = ZoneId.of("Europe/Zurich");

    private record Folge(String art, Pattern muster) {
    }

    private record Monatlich(String art, String bezug, String gruppe, String nurMonat) {
    }

    private final WerkzeugDaten daten;
    private final MandantEinstellungen einstellungen;
    private final Config konfiguration;
    private final Clock uhr;

    @Inject
    public KonfigurierteLuecken(WerkzeugDaten daten, MandantEinstellungen einstellungen, Config konfiguration) {
        this(daten, einstellungen, konfiguration, Clock.system(ZONE));
    }

    KonfigurierteLuecken(WerkzeugDaten daten, MandantEinstellungen einstellungen, Config konfiguration, Clock uhr) {
        this.daten = daten;
        this.einstellungen = einstellungen;
        this.konfiguration = konfiguration;
        this.uhr = uhr;
    }

    @Override
    public Bericht erstellen(MandantCode mandant, int geschaeftsjahr, Sichtregel sicht) {
        List<Folge> folgen = folgen(mandant);
        List<Monatlich> monatlich = monatlich(mandant);
        List<Kategorie> kategorien = new ArrayList<>();
        Set<String> arten = new LinkedHashSet<>();
        for (Folge folge : folgen) {
            arten.add(folge.art());
            kategorien.add(folge(folge, geschaeftsjahr, sicht));
        }
        if (!monatlich.isEmpty()) {
            Monatsluecken.Spanne spanne = Monatsluecken
                    .monateDes(einstellungen.lesen(mandant).geschaeftsjahresende().monatTag(), geschaeftsjahr)
                    .orElse(null);
            YearMonth letzter = YearMonth.from(LocalDate.now(uhr)).minusMonths(1);
            for (Monatlich regel : monatlich) {
                arten.add(regel.art());
                kategorien.add(monatlich(regel, spanne, letzter, sicht));
            }
        }
        return new Bericht(geschaeftsjahr, kategorien, daten.belege(arten, sicht));
    }

    private Kategorie folge(Folge regel, int geschaeftsjahr, Sichtregel sicht) {
        List<String> nummern = daten.belegnummern(regel.art(), geschaeftsjahr, sicht);
        Nummernfolge.Ergebnis e = Nummernfolge.pruefen(regel.muster(),
                Nummernfolge.hatGruppe(regel.muster(), "jahr") ? geschaeftsjahr : null, nummern, Reihe.FEHLEND_MAX);
        List<Reihe> reihen = e.vorhanden() == 0 ? List.of()
                : List.of(new Reihe(null, null, e.hoechste(), e.vorhanden(), e.fehlendGesamt(), e.fehlend()));
        return new Kategorie(Regel.FOLGE, regel.art(), null, nummern.size(), null, reihen.size(), reihen);
    }

    private Kategorie monatlich(Monatlich regel, Monatsluecken.Spanne spanne, YearMonth letzter, Sichtregel sicht) {
        List<WerkzeugDaten.Monatszeile> zeilen = daten.monate(
                new WerkzeugDaten.Monatsquelle(regel.art(), regel.bezug(), regel.gruppe(), regel.nurMonat()), sicht);
        Map<String, Set<YearMonth>> jeGruppe = new LinkedHashMap<>();
        long belege = 0;
        long ohneZuordnung = 0;
        for (WerkzeugDaten.Monatszeile z : zeilen) {
            belege += z.belege();
            if (z.gruppe() == null) {
                ohneZuordnung += z.belege();
            } else {
                jeGruppe.computeIfAbsent(z.gruppe(), g -> new HashSet<>()).add(z.monat());
            }
        }
        List<Reihe> reihen = new ArrayList<>();
        if (spanne != null) {
            for (Map.Entry<String, Set<YearMonth>> eintrag : jeGruppe.entrySet()) {
                Monatsluecken.Ergebnis e = Monatsluecken.pruefen(eintrag.getValue(), spanne, letzter);
                if (e.erster().isAfter(spanne.letzter())) {
                    continue;
                }
                long vorhanden = eintrag.getValue().stream()
                        .filter(m -> !m.isBefore(spanne.erster()) && !m.isAfter(spanne.letzter())).count();
                List<String> fehlend = e.fehlend().stream().limit(Reihe.FEHLEND_MAX).map(YearMonth::toString).toList();
                reihen.add(new Reihe(eintrag.getKey().isEmpty() ? null : eintrag.getKey(), e.erster().toString(), null,
                        vorhanden, e.fehlend().size(), fehlend));
            }
        }
        reihen.sort(Comparator.comparingLong(Reihe::anzahlFehlend).reversed()
                .thenComparing(r -> r.gruppe() == null ? "" : r.gruppe(), String.CASE_INSENSITIVE_ORDER));
        List<Reihe> sichtbar = reihen.size() > Kategorie.REIHEN_MAX ? reihen.subList(0, Kategorie.REIHEN_MAX) : reihen;
        return new Kategorie(Regel.ERWARTET, regel.art(), "MONAT", belege, ohneZuordnung == 0 ? null : ohneZuordnung,
                reihen.size(), sichtbar);
    }

    // --- Konfiguration ----------------------------------------------------------------------------------------------

    private List<Folge> folgen(MandantCode mandant) {
        List<Folge> folgen = new ArrayList<>();
        for (String[] eintrag : eintraege(mandant, "folge")) {
            try {
                Pattern muster = Pattern.compile(eintrag[1]);
                if (!Nummernfolge.hatGruppe(muster, "nr")) {
                    throw ungueltig(mandant, "folge");
                }
                folgen.add(new Folge(eintrag[0], muster));
            } catch (PatternSyntaxException e) {
                throw ungueltig(mandant, "folge");
            }
        }
        return List.copyOf(folgen);
    }

    private List<Monatlich> monatlich(MandantCode mandant) {
        Map<String, String> gruppen = zuordnung(mandant, "gruppe", w -> w.equals(WerkzeugDaten.PARTNER) || FELD.matcher(w).matches());
        Map<String, String> nurMonat = zuordnung(mandant, "nur-monat", w -> FELD.matcher(w).matches());
        List<Monatlich> regeln = new ArrayList<>();
        for (String[] eintrag : eintraege(mandant, "erwartet-monatlich")) {
            if (!eintrag[1].equals(WerkzeugDaten.BELEGDATUM) && !FELD.matcher(eintrag[1]).matches()) {
                throw ungueltig(mandant, "erwartet-monatlich");
            }
            regeln.add(new Monatlich(eintrag[0], eintrag[1], gruppen.get(eintrag[0]), nurMonat.get(eintrag[0])));
        }
        Set<String> arten = new HashSet<>();
        regeln.forEach(r -> arten.add(r.art()));
        if (!arten.containsAll(gruppen.keySet())) {
            throw ungueltig(mandant, "gruppe");
        }
        if (!arten.containsAll(nurMonat.keySet())) {
            throw ungueltig(mandant, "nur-monat");
        }
        return List.copyOf(regeln);
    }

    private Map<String, String> zuordnung(MandantCode mandant, String schluessel,
                                          java.util.function.Predicate<String> wert) {
        Map<String, String> karte = new LinkedHashMap<>();
        for (String[] eintrag : eintraege(mandant, schluessel)) {
            if (!wert.test(eintrag[1]) || karte.put(eintrag[0], eintrag[1]) != null) {
                throw ungueltig(mandant, schluessel);
            }
        }
        return karte;
    }

    /** {@code ART:Wert}-Einträge; geteilt wird am ersten Doppelpunkt, die Art ist geprüft. */
    private List<String[]> eintraege(MandantCode mandant, String schluessel) {
        // Muster stehen als ein Text, nie als Liste: Die Listenumwandlung der Konfiguration frisst jeden Rückwärtsstrich
        List<String> werte = konfiguration.getOptionalValue(praefix(mandant) + schluessel, String.class)
                .map(t -> List.of(t.split(schluessel.equals("folge") ? ";" : ",")))
                .orElse(List.of());
        List<String[]> eintraege = new ArrayList<>();
        for (String wert : werte) {
            String[] teile = wert.strip().split(":", 2);
            if (teile.length != 2 || !ART.matcher(teile[0]).matches() || teile[1].isBlank()) {
                throw ungueltig(mandant, schluessel);
            }
            eintraege.add(new String[]{teile[0], teile[1].strip()});
        }
        return eintraege;
    }

    private static String praefix(MandantCode mandant) {
        return "archiv.mandant." + mandant.wert() + ".luecken.";
    }

    private static IllegalStateException ungueltig(MandantCode mandant, String schluessel) {
        return new IllegalStateException(praefix(mandant) + schluessel + " ist ungültig");
    }
}
