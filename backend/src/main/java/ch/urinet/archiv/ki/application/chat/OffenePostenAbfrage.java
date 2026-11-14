package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.Werteformat;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Werkzeug {@code offene_posten(stichtag?, richtung?)} (Spec 02, Req 3.4, 5.2): Rechnungen der konfigurierten Arten
 * ({@link FinanzArten}) mit Fälligkeit von {@value #TAGE_VOR} Tagen vor bis {@value #TAGE_NACH} Tage nach dem Stichtag
 * (Standard heute in {@code Europe/Zurich}, Richtung {@code EINNAHME}), archiviert, bestätigt und sichtbar, nach
 * Fälligkeit sortiert, höchstens {@value #HOECHSTENS} mit der Gesamtzahl. Der Zahlstatus kommt aus der
 * {@link Zahlungsquelle}; ohne AbaNinja ist er {@code UNBEKANNT} und der Hinweis {@value #HINWEIS_ZAHLSTATUS} steht
 * dabei. {@code ueberfaellig} heisst nur: Fälligkeit vor dem Stichtag und nicht als bezahlt bekannt.
 */
@ApplicationScoped
public class OffenePostenAbfrage {

    public static final int TAGE_VOR = 90;
    public static final int TAGE_NACH = 30;
    public static final int HOECHSTENS = 50;
    public static final String HINWEIS_ZAHLSTATUS = "Zahlstatus nicht bekannt, AbaNinja nicht verbunden";
    public static final String HINWEIS_UEBERFAELLIG = "ueberfaellig: Fälligkeit liegt vor dem Stichtag, Zahlung unbekannt";
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    public record Posten(String id, String belegnummer, String partner, String betrag, String faelligkeit,
                         boolean ueberfaellig, String zahlstatus, String link) {
    }

    /** Req 4.1: Zeitpunkt, Zahl aller Posten und die Basis, z. B. «nach Fälligkeit, 3 Rechnungen». */
    public record Datenstand(String zeitpunkt, long anzahl, String basis) {
    }

    public record Fenster(String von, String bis) {
    }

    public record Antwort(String stichtag, String richtung, Fenster fenster, List<Posten> posten, long gesamt,
                          Datenstand datenstand, List<String> hinweise) {
    }

    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final FinanzArten arten;
    private final Zahlungsquelle zahlung;
    private final Clock uhr;

    @Inject
    public OffenePostenAbfrage(WerkzeugDaten daten, WerkzeugTransaktion transaktion, FinanzArten arten,
                               Zahlungsquelle zahlung) {
        this(daten, transaktion, arten, zahlung, Clock.systemUTC());
    }

    OffenePostenAbfrage(WerkzeugDaten daten, WerkzeugTransaktion transaktion, FinanzArten arten,
                        Zahlungsquelle zahlung, Clock uhr) {
        this.daten = daten;
        this.transaktion = transaktion;
        this.arten = arten;
        this.zahlung = zahlung;
        this.uhr = uhr;
    }

    public Antwort offenePosten(WerkzeugKontext kontext, String stichtagText, String richtungText) {
        LocalDate heute = uhr.instant().atZone(ZUERICH).toLocalDate();
        LocalDate stichtag = Werkzeugparameter.datum("stichtag", stichtagText, false);
        if (stichtag == null) {
            stichtag = heute;
        }
        WerkzeugDaten.Richtung richtung = Werkzeugparameter.wert("richtung", richtungText,
                WerkzeugDaten.Richtung.class, WerkzeugDaten.Richtung.EINNAHME);
        List<WerkzeugDaten.ArtRegel> artenListe = arten.arten(kontext.mandant(), richtung);
        if (artenListe.isEmpty()) {
            throw new WerkzeugFehler("für diese Richtung sind keine Arten festgelegt");
        }
        LocalDate von = stichtag.minusDays(TAGE_VOR);
        LocalDate bis = stichtag.plusDays(TAGE_NACH);
        WerkzeugDaten.OffenePostenSeite seite = transaktion.neu(() -> {
            daten.nurLesend();
            return daten.offenePosten(richtung, artenListe, von, bis, kontext.sicht(), HOECHSTENS);
        });
        LocalDate stichtagFest = stichtag;
        List<Posten> posten = seite.posten().stream().map(p -> posten(p, stichtagFest)).toList();
        List<String> hinweise = new ArrayList<>();
        if (!zahlung.verbunden()) {
            hinweise.add(HINWEIS_ZAHLSTATUS);
            hinweise.add(HINWEIS_UEBERFAELLIG);
        }
        if (!kontext.sicht().uneingeschraenkt()) {
            hinweise.add(FristenAbfrage.HINWEIS_SICHT);
        }
        String einheit = seite.gesamt() == 1 ? "Rechnung" : "Rechnungen";
        return new Antwort(Werteformat.datum(stichtag), richtung.name(),
                new Fenster(Werteformat.datum(von), Werteformat.datum(bis)), posten, seite.gesamt(),
                new Datenstand(Werteformat.zeitpunkt(uhr.instant()), seite.gesamt(),
                        "nach Fälligkeit, " + seite.gesamt() + " " + einheit),
                hinweise.isEmpty() ? null : List.copyOf(hinweise));
    }

    private Posten posten(WerkzeugDaten.OffenerPosten p, LocalDate stichtag) {
        String status = zahlung.zahlstatus(p.zahlstatus());
        boolean bezahlt = "BEZAHLT".equalsIgnoreCase(status);
        return new Posten(p.id().toString(), p.belegnummer(), p.partner(), Werteformat.betrag(p.betrag(), p.waehrung()),
                Werteformat.datum(p.faelligkeit()), !bezahlt && p.faelligkeit().isBefore(stichtag), status,
                DokumenteSuchen.link(p.id()));
    }
}
