package ch.urinet.archiv.ki.adapter.out.chat;

import ch.urinet.archiv.ki.application.chat.Lueckenbericht;
import ch.urinet.archiv.ki.application.chat.WerkzeugAuswahl;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.application.chat.WerkzeugFehler;
import ch.urinet.archiv.ki.domain.chat.Monatsluecken;
import ch.urinet.archiv.ki.domain.chat.Nummernfolge;
import ch.urinet.archiv.ki.domain.chat.Nummernfolge.ZeitgrenzeUeberschritten;
import ch.urinet.archiv.konfiguration.application.KonfigurationLesen;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.MonthDay;
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

/** Liefert Lücken nach den aktiven Regeln {@code FOLGE} und {@code ERWARTET} der Mandantenkonfiguration. */
@ApplicationScoped
public class KonfigurierteLuecken implements Lueckenbericht {

    private static final Logger LOG = Logger.getLogger(KonfigurierteLuecken.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Zurich");
    private static final String KEINE_MONATE = "Für dieses Geschäftsjahr liegen keine abgeschlossenen vollen Monate vor; "
            + "Monatslücken wurden nicht geprüft.";

    private record Folge(String art, Pattern muster, String feld, boolean musterAusArt,
                         ch.urinet.archiv.konfiguration.domain.Regel.Zeitraum zeitraum,
                         String zeitraumFeld, int beginn) {
    }

    private record Monatlich(String art, String bezug, YearMonth ab, String nurMonat, String gruppe) {
    }

    private final WerkzeugDaten daten;
    private final KonfigurationLesen konfigurationLesen;
    private final Clock uhr;
    private final Duration musterZeitgrenze;

    private static final class BerichtBudget {
        private int folgewerte;

        int verbleibend() {
            return Nummernfolge.BELEGNUMMERN_MAX - folgewerte;
        }

        void hinzufuegen(int anzahl, String regel) {
            if (anzahl > verbleibend()) {
                LOG.warnf("Lückenbericht für Regel %s wegen zu vieler Belegnummern abgebrochen", regel);
                throw new IllegalStateException("Zu viele Belegnummern für den Lückenbericht");
            }
            folgewerte += anzahl;
        }
    }

    @Inject
    public KonfigurierteLuecken(WerkzeugDaten daten, KonfigurationLesen konfigurationLesen,
                               @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration musterZeitgrenze) {
        this(daten, konfigurationLesen, Clock.system(ZONE), musterZeitgrenze);
    }

    KonfigurierteLuecken(WerkzeugDaten daten, KonfigurationLesen konfigurationLesen) {
        this(daten, konfigurationLesen, Clock.system(ZONE), Duration.ofMillis(50));
    }

    KonfigurierteLuecken(WerkzeugDaten daten, KonfigurationLesen konfigurationLesen, Clock uhr) {
        this(daten, konfigurationLesen, uhr, Duration.ofMillis(50));
    }

    KonfigurierteLuecken(WerkzeugDaten daten, KonfigurationLesen konfigurationLesen, Clock uhr,
                         Duration musterZeitgrenze) {
        this.daten = daten;
        this.konfigurationLesen = konfigurationLesen;
        this.uhr = uhr;
        this.musterZeitgrenze = musterZeitgrenze;
    }

    @Override
    public Bericht erstellen(MandantCode mandant, int geschaeftsjahr, Sichtregel sicht) {
        // Verteidigung in der Tiefe (Req 5.4): auch ohne Werkzeugauswahl nur für Rollen, die A3 und A4 voll sehen
        if (!WerkzeugAuswahl.vollstaendigkeitErlaubt(sicht)) {
            throw new WerkzeugFehler(WerkzeugFehler.NICHT_VERFUEGBAR);
        }
        return erstellenAusRegeln(geschaeftsjahr, sicht, konfigurationLesen.aktuell());
    }

    private Bericht erstellenAusRegeln(int geschaeftsjahr, Sichtregel sicht, Konfiguration konfiguration) {
        Map<String, Konfiguration.Art> arten = new LinkedHashMap<>();
        konfiguration.arten().stream().filter(Konfiguration.Art::aktiv).forEach(art -> arten.put(art.code(), art));
        List<Kategorie> kategorien = new ArrayList<>();
        Set<String> betroffeneArten = new LinkedHashSet<>();
        BerichtBudget budget = new BerichtBudget();
        for (ch.urinet.archiv.konfiguration.domain.Regel regel : konfiguration.regeln()) {
            if (!regel.aktiv() || !regel.typ().equals("FOLGE") && !regel.typ().equals("ERWARTET")) {
                continue;
            }
            List<Konfiguration.Art> regelArten = regel.art() == null
                    ? List.copyOf(arten.values())
                    : arten.containsKey(regel.art()) ? List.of(arten.get(regel.art())) : List.of();
            ch.urinet.archiv.konfiguration.domain.Regel.Parameter parameter = regel.typisierteParameter();
            for (Konfiguration.Art art : regelArten) {
                betroffeneArten.add(art.code());
                if (parameter instanceof ch.urinet.archiv.konfiguration.domain.Regel.Folge folge) {
                    Folge bericht = folge(art, folge);
                    kategorien.add(folge(bericht, geschaeftsjahr, sicht, budget, regel.code()));
                } else if (parameter instanceof ch.urinet.archiv.konfiguration.domain.Regel.Erwartet erwartet) {
                    if ("MONAT".equals(erwartet.je())) {
                        kategorien.add(monatlich(new Monatlich(art.code(), bezug(erwartet.datum()),
                                        erwartet.ab() == null ? null : YearMonth.from(LocalDate.parse(erwartet.ab())),
                                        erwartet.nurMonat(), erwartet.gruppe()),
                                spanne(konfiguration, geschaeftsjahr),
                                YearMonth.from(LocalDate.now(uhr)).minusMonths(1), sicht));
                    } else {
                        kategorien.add(jahrlich(art.code(), erwartet, geschaeftsjahr, geschaeftsjahrEnde(konfiguration),
                                sicht));
                    }
                }
            }
        }
        return new Bericht(geschaeftsjahr, kategorien, daten.belege(betroffeneArten, sicht));
    }

    private Folge folge(Konfiguration.Art art, ch.urinet.archiv.konfiguration.domain.Regel.Folge regel) {
        Pattern muster;
        if (regel.muster()) {
            muster = art.belegnummerMuster().stream().map(Pattern::compile)
                    .filter(p -> Nummernfolge.hatGruppe(p, "nr")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Belegnummer-Muster für Folgenregel fehlt"));
        } else {
            muster = Pattern.compile("^(?<nr>[0-9]+)$");
        }
        return new Folge(art.code(), muster, regel.feld(), regel.muster(), regel.zeitraum(),
                regel.zeitraumFeld(), regel.beginn());
    }

    private Kategorie folge(Folge regel, int geschaeftsjahr, Sichtregel sicht, BerichtBudget budget, String regelCode) {
        List<String> nummern = daten.folgewerte(new WerkzeugDaten.Folgequelle(regel.art(), regel.feld(),
                regel.musterAusArt(), regel.zeitraum().name(), regel.zeitraumFeld(), geschaeftsjahr), sicht,
                budget.verbleibend() + 1);
        budget.hinzufuegen(nummern.size(), regelCode);
        Nummernfolge.Ergebnis e;
        try {
            e = Nummernfolge.pruefen(regel.muster(),
                    regel.zeitraum() == ch.urinet.archiv.konfiguration.domain.Regel.Zeitraum.GESCHAEFTSJAHR
                            && Nummernfolge.hatGruppe(regel.muster(), "jahr") ? geschaeftsjahr : null,
                    nummern, regel.beginn(), Reihe.FEHLEND_MAX, Nummernfolge.Frist.cpu(musterZeitgrenze));
        } catch (ZeitgrenzeUeberschritten eUeberschritten) {
            LOG.warnf("Lückenbericht für Regel %s nach %s wegen Muster-Zeitgrenze abgebrochen",
                    regelCode, musterZeitgrenze);
            throw new IllegalStateException("Muster-Zeitgrenze für den Lückenbericht überschritten", eUeberschritten);
        }
        List<Reihe> reihen = e.vorhanden() == 0 ? List.of()
                : List.of(new Reihe(null, null, e.hoechste(), e.vorhanden(), e.fehlendGesamt(), e.fehlend()));
        return new Kategorie(Regel.FOLGE, regel.art(), null, null, nummern.size(), null, reihen.size(), reihen);
    }

    private Kategorie monatlich(Monatlich regel, Monatsluecken.Spanne spanne, YearMonth letzter, Sichtregel sicht) {
        List<WerkzeugDaten.Monatszeile> zeilen = daten.monate(
                new WerkzeugDaten.Monatsquelle(regel.art(), regel.bezug(), regel.gruppe(), regel.nurMonat()), sicht);
        Map<String, Set<YearMonth>> monateJeSchluessel = new LinkedHashMap<>();
        Map<String, String> anzeigeJeSchluessel = new LinkedHashMap<>();
        long belege = 0;
        long ohneZuordnung = 0;
        for (WerkzeugDaten.Monatszeile z : zeilen) {
            belege += z.belege();
            if (z.schluessel() == null) {
                ohneZuordnung += z.belege();
            } else {
                monateJeSchluessel.computeIfAbsent(z.schluessel(), g -> new HashSet<>()).add(z.monat());
                if (z.gruppe() != null) {
                    anzeigeJeSchluessel.merge(z.schluessel(), z.gruppe(),
                            (vorhanden, neu) -> vorhanden.compareTo(neu) <= 0 ? vorhanden : neu);
                }
            }
        }
        // ohne Gruppierung und ohne Dokument gibt es mit «ab» trotzdem eine Reihe (alle Monate ab «ab» fehlen)
        if (regel.gruppe() == null && monateJeSchluessel.isEmpty() && regel.ab() != null) {
            monateJeSchluessel.put("", new HashSet<>());
        }
        List<Reihe> reihen = new ArrayList<>();
        boolean monateZuPruefen = spanne != null && !letzter.isBefore(spanne.erster());
        if (monateZuPruefen) {
            for (Map.Entry<String, Set<YearMonth>> eintrag : monateJeSchluessel.entrySet()) {
                Monatsluecken.Ergebnis e = Monatsluecken.pruefen(eintrag.getValue(), spanne, letzter, regel.ab());
                YearMonth beginn = regel.ab() != null ? regel.ab() : e.erster();
                if (beginn.isAfter(spanne.letzter())) {
                    continue;
                }
                long vorhanden = eintrag.getValue().stream()
                        .filter(m -> !m.isBefore(spanne.erster()) && !m.isAfter(spanne.letzter())).count();
                List<String> fehlend = e.fehlend().stream().limit(Reihe.FEHLEND_MAX).map(YearMonth::toString).toList();
                String gruppe = anzeigeJeSchluessel.get(eintrag.getKey());
                reihen.add(new Reihe(gruppe == null || gruppe.isEmpty() ? null : gruppe,
                        beginn.toString(), null, vorhanden, e.fehlend().size(), fehlend));
            }
        }
        reihen.sort(Comparator.comparingLong(Reihe::anzahlFehlend).reversed()
                .thenComparing(r -> r.gruppe() == null ? "" : r.gruppe(), String.CASE_INSENSITIVE_ORDER));
        List<Reihe> sichtbar = reihen.size() > Kategorie.REIHEN_MAX ? reihen.subList(0, Kategorie.REIHEN_MAX) : reihen;
        return new Kategorie(Regel.ERWARTET, regel.art(), "MONAT", monateZuPruefen ? null : KEINE_MONATE, belege,
                ohneZuordnung == 0 ? null : ohneZuordnung, reihen.size(), sichtbar);
    }

    private Monatsluecken.Spanne spanne(Konfiguration konfiguration, int geschaeftsjahr) {
        return Monatsluecken.monateDes(
                        new Geschaeftsjahresende(konfiguration.einstellungen().geschaeftsjahrEnde()).monatTag(),
                        geschaeftsjahr)
                .orElse(null);
    }

    private static MonthDay geschaeftsjahrEnde(Konfiguration konfiguration) {
        return new Geschaeftsjahresende(konfiguration.einstellungen().geschaeftsjahrEnde()).monatTag();
    }

    /** Geschäftsjahr eines Tages, benannt nach dem Kalenderjahr seines Endes. */
    static int geschaeftsjahrDes(LocalDate tag, MonthDay ende) {
        return MonthDay.from(tag).isAfter(ende) ? tag.getYear() + 1 : tag.getYear();
    }

    /**
     * Geschäftsjahre ohne Dokument (Spec 05, Req 5.1, design.md «Regeltypen»): ab {@code ab} oder dem ersten Geschäftsjahr
     * mit einem bestätigten Dokument bis zum letzten abgeschlossenen Geschäftsjahr, höchstens bis zum angefragten.
     */
    private Kategorie jahrlich(String art, ch.urinet.archiv.konfiguration.domain.Regel.Erwartet regel,
                               int geschaeftsjahr, MonthDay ende, Sichtregel sicht) {
        List<WerkzeugDaten.Jahreszeile> zeilen = daten.jahre(
                new WerkzeugDaten.Monatsquelle(art, bezug(regel.datum()), null, null), ende, sicht);
        Set<Integer> vorhanden = new HashSet<>();
        zeilen.forEach(z -> vorhanden.add(z.geschaeftsjahr()));
        long belege = zeilen.stream().mapToLong(WerkzeugDaten.Jahreszeile::belege).sum();
        Integer erster = regel.ab() != null ? Integer.valueOf(geschaeftsjahrDes(LocalDate.parse(regel.ab()), ende))
                : vorhanden.stream().min(Integer::compareTo).orElse(null);
        LocalDate heute = LocalDate.now(uhr);
        int letzterAbgeschlossen = geschaeftsjahrDes(heute, ende) - 1;
        int bis = Math.min(geschaeftsjahr, letzterAbgeschlossen);
        if (erster == null || erster > bis) {
            return new Kategorie(Regel.ERWARTET, art, "JAHR", null, belege, null, 0, List.of());
        }
        List<String> fehlend = new ArrayList<>();
        long fehlendGesamt = 0;
        long jahreVorhanden = 0;
        for (int jahr = erster; jahr <= bis; jahr++) {
            if (vorhanden.contains(jahr)) {
                jahreVorhanden++;
            } else {
                fehlendGesamt++;
                if (fehlend.size() < Reihe.FEHLEND_MAX) {
                    fehlend.add(Integer.toString(jahr));
                }
            }
        }
        List<Reihe> reihen = List.of(new Reihe(null, Integer.toString(erster), Integer.toString(bis),
                jahreVorhanden, fehlendGesamt, fehlend));
        return new Kategorie(Regel.ERWARTET, art, "JAHR", null, belege, null, reihen.size(), reihen);
    }

    private static String bezug(String feld) {
        return feld == null || feld.equals("BELEGDATUM") ? WerkzeugDaten.BELEGDATUM : feld;
    }
}
