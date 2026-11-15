package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.Werteformat;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Werkzeug {@code kennzahl(messgroesse, von, bis, gruppierung, basis)} (Spec 02, Req 3.2, 3.3, 5.2): Summe der
 * Einnahmen oder Ausgaben in CHF aus den archivierten, bestätigten und sichtbaren Belegen der konfigurierten Arten
 * ({@link FinanzArten}), vom Archiv gerechnet, nie vom Sprachmodell. Spanne höchstens {@value #JAHRE_MAX} Jahre.
 * Fremdwährungsbelege zählen nicht in die Summe, ihre Zahl steht in der Antwort. Mit Basis
 * {@code ZAHLUNGSDATUM} gibt es ohne Zahlungsquelle ({@link Zahlungsquelle}, AbaNinja) keine Zahl, nur den Hinweis
 * {@value #HINWEIS_ZAHLUNGSDATUM}. Bei eingeschränkter Sicht steht {@value FristenAbfrage#HINWEIS_SICHT} dabei.
 */
@ApplicationScoped
public class KennzahlAbfrage {

    public static final int JAHRE_MAX = 10;
    public static final int BELEGE_MAX = 50;
    public static final String HINWEIS_ZAHLUNGSDATUM = "Zahlungsdatum unbekannt (keine AbaNinja-Verbindung)";
    public static final String HINWEIS_GEKUERZT = "Zeilen gekürzt, die Summe gilt für alle Belege";
    public static final String GRUPPE_OHNE_PARTNER = "(ohne Partner)";
    public static final String GRUPPE_TOTAL = "Total";

    /** Was gezählt wird. */
    public enum Messgroesse {
        EINNAHMEN(WerkzeugDaten.Richtung.EINNAHME, "Rechnungsdatum", "Rechnungen", "Rechnung"),
        AUSGABEN(WerkzeugDaten.Richtung.AUSGABE, "Belegdatum", "Belege", "Beleg");

        final WerkzeugDaten.Richtung richtung;
        final String datumName;
        final String mehrzahl;
        final String einzahl;

        Messgroesse(WerkzeugDaten.Richtung richtung, String datumName, String mehrzahl, String einzahl) {
            this.richtung = richtung;
            this.datumName = datumName;
            this.mehrzahl = mehrzahl;
            this.einzahl = einzahl;
        }
    }

    public record Zeile(String gruppe, String betrag, long anzahl) {
    }

    /** Req 4.1: Zeitpunkt, Zahl der Belege und die zeitliche Basis, z. B. «nach Rechnungsdatum, 3 Rechnungen». */
    public record Datenstand(String zeitpunkt, long anzahl, String basis) {
    }

    /**
     * {@code summe} und {@code datenstand} sind ohne Zahlungsdatum {@code null}; {@code belegIds} höchstens
     * {@value #BELEGE_MAX}; {@code fremdwaehrungAusgeschlossen}: Belege in anderer Währung im selben Filter.
     */
    public record Antwort(String messgroesse, String von, String bis, String gruppierung, String basis,
                          List<Zeile> zeilen, String summe, Datenstand datenstand, List<String> belegIds,
                          Long fremdwaehrungAusgeschlossen, List<String> hinweise) {
    }

    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final FinanzArten arten;
    private final Zahlungsquelle zahlung;
    private final Clock uhr;

    @Inject
    public KennzahlAbfrage(WerkzeugDaten daten, WerkzeugTransaktion transaktion, FinanzArten arten,
                           Zahlungsquelle zahlung) {
        this(daten, transaktion, arten, zahlung, Clock.systemUTC());
    }

    KennzahlAbfrage(WerkzeugDaten daten, WerkzeugTransaktion transaktion, FinanzArten arten, Zahlungsquelle zahlung,
                    Clock uhr) {
        this.daten = daten;
        this.transaktion = transaktion;
        this.arten = arten;
        this.zahlung = zahlung;
        this.uhr = uhr;
    }

    public Antwort kennzahl(WerkzeugKontext kontext, String messgroesseText, String vonText, String bisText,
                            String gruppierungText, String basisText) {
        Messgroesse messgroesse = Werkzeugparameter.wert("messgroesse", messgroesseText, Messgroesse.class, null);
        LocalDate von = Werkzeugparameter.datum("von", vonText, true);
        LocalDate bis = Werkzeugparameter.datum("bis", bisText, true);
        WerkzeugDaten.Gruppierung gruppierung = Werkzeugparameter.wert("gruppierung", gruppierungText,
                WerkzeugDaten.Gruppierung.class, WerkzeugDaten.Gruppierung.KEINE);
        WerkzeugDaten.Basis basis = Werkzeugparameter.wert("basis", basisText, WerkzeugDaten.Basis.class,
                WerkzeugDaten.Basis.BELEGDATUM);
        if (von.isAfter(bis)) {
            throw WerkzeugFehler.ungueltig("von");
        }
        if (bis.isAfter(von.plusYears(JAHRE_MAX))) {
            throw WerkzeugFehler.ungueltig("bis");
        }
        List<String> hinweise = new ArrayList<>();
        if (basis == WerkzeugDaten.Basis.ZAHLUNGSDATUM && !zahlung.verbunden()) {
            hinweise.add(HINWEIS_ZAHLUNGSDATUM);
            return new Antwort(messgroesse.name(), Werteformat.datum(von), Werteformat.datum(bis), gruppierung.name(),
                    basis.name(), List.of(), null, null, List.of(), null, List.copyOf(hinweise));
        }
        List<WerkzeugDaten.ArtRegel> artenListe = arten.arten(kontext.mandant(), messgroesse.richtung);
        if (artenListe.isEmpty()) {
            throw new WerkzeugFehler("für diese Messgrösse sind keine Arten festgelegt");
        }
        WerkzeugDaten.KennzahlAnfrage anfrage = new WerkzeugDaten.KennzahlAnfrage(messgroesse.richtung, artenListe, von,
                bis, gruppierung, basis, BELEGE_MAX);
        WerkzeugDaten.KennzahlSeite seite = transaktion.neu(() -> {
            daten.nurLesend();
            return daten.kennzahl(anfrage, kontext.sicht());
        });
        if (!kontext.sicht().uneingeschraenkt()) {
            hinweise.add(FristenAbfrage.HINWEIS_SICHT);
        }
        if (seite.gekuerzt()) {
            hinweise.add(HINWEIS_GEKUERZT);
        }
        String datumName = basis == WerkzeugDaten.Basis.BELEGDATUM ? messgroesse.datumName : "Zahlungsdatum";
        String einheit = seite.anzahl() == 1 ? messgroesse.einzahl : messgroesse.mehrzahl;
        return new Antwort(messgroesse.name(), Werteformat.datum(von), Werteformat.datum(bis), gruppierung.name(),
                basis.name(), seite.zeilen().stream().map(z -> zeile(gruppierung, z)).toList(),
                Werteformat.chf(seite.summe()),
                new Datenstand(Werteformat.zeitpunkt(uhr.instant()), seite.anzahl(),
                        "nach " + datumName + ", " + seite.anzahl() + " " + einheit),
                seite.belege().stream().map(Object::toString).toList(), seite.fremdwaehrung(),
                hinweise.isEmpty() ? null : List.copyOf(hinweise));
    }

    private static Zeile zeile(WerkzeugDaten.Gruppierung gruppierung, WerkzeugDaten.KennzahlZeile z) {
        String gruppe = z.gruppe();
        if (gruppe == null) {
            gruppe = gruppierung == WerkzeugDaten.Gruppierung.KEINE ? GRUPPE_TOTAL : GRUPPE_OHNE_PARTNER;
        }
        return new Zeile(gruppe, Werteformat.chf(z.betrag()), z.anzahl());
    }

}
