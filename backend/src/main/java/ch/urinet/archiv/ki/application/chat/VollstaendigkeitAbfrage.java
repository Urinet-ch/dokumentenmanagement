package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.Werteformat;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Werkzeug {@code vollstaendigkeit(geschaeftsjahr)} (Spec 02, Req 3.6, 4.2, 5.2): Lücken je Kategorie aus dem
 * {@link Lueckenbericht} (fehlende Nummern, fehlende Monate), dazu der Datenstand und der Teil, den das Archiv ohne
 * AbaNinja nicht prüfen kann, als ausdrückliche Lücke ({@value #ABANINJA}). Nur für Rollen, die A3 und A4
 * uneingeschränkt sehen; die Prüfung wiederholt sich hier, auch wenn {@link Werkzeuglauf} sie schon gemacht hat. Das
 * Geschäftsjahr liegt von {@value #JAHR_MIN} bis zum nächsten Kalenderjahr.
 */
@ApplicationScoped
public class VollstaendigkeitAbfrage {

    public static final int JAHR_MIN = 2000;
    public static final String ABANINJA = "Lieferantenbelege ohne Archivdokument: nur mit AbaNinja";
    public static final String KEINE_REGELN = "Für diesen Mandanten sind keine Regeln für Lücken eingerichtet";

    /**
     * {@code nichtGeprueft}: Teile der Frage, die dieses Werkzeug nicht beantworten kann; {@code hinweis} nur ohne
     * Kategorien.
     */
    public record Antwort(int geschaeftsjahr, List<Lueckenbericht.Kategorie> kategorien, List<String> nichtGeprueft,
                          FristenAbfrage.Datenstand datenstand, String hinweis) {

        public Antwort {
            kategorien = List.copyOf(kategorien);
            nichtGeprueft = List.copyOf(nichtGeprueft);
        }
    }

    private final Lueckenbericht bericht;
    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final Clock uhr;

    @Inject
    public VollstaendigkeitAbfrage(Lueckenbericht bericht, WerkzeugDaten daten, WerkzeugTransaktion transaktion) {
        this(bericht, daten, transaktion, Clock.system(ZoneId.of("Europe/Zurich")));
    }

    VollstaendigkeitAbfrage(Lueckenbericht bericht, WerkzeugDaten daten, WerkzeugTransaktion transaktion, Clock uhr) {
        this.bericht = bericht;
        this.daten = daten;
        this.transaktion = transaktion;
        this.uhr = uhr;
    }

    public Antwort vollstaendigkeit(WerkzeugKontext kontext, Integer geschaeftsjahr) {
        if (!WerkzeugAuswahl.vollstaendigkeitErlaubt(kontext.sicht())) {
            throw new WerkzeugFehler(WerkzeugFehler.NICHT_VERFUEGBAR);
        }
        if (geschaeftsjahr == null || geschaeftsjahr < JAHR_MIN || geschaeftsjahr > LocalDate.now(uhr).getYear() + 1) {
            throw WerkzeugFehler.ungueltig("geschaeftsjahr");
        }
        Lueckenbericht.Bericht b = transaktion.neu(() -> {
            daten.nurLesend();
            return bericht.erstellen(kontext.mandant(), geschaeftsjahr, kontext.sicht());
        });
        return new Antwort(b.geschaeftsjahr(), b.kategorien(), List.of(ABANINJA),
                new FristenAbfrage.Datenstand(Werteformat.zeitpunkt(uhr.instant()), b.belege()),
                b.kategorien().isEmpty() ? KEINE_REGELN : null);
    }
}
