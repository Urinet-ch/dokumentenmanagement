package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.konfiguration.application.Funktionen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Welche Werkzeuge der Agent für eine Chat-Anfrage angeboten bekommt (Spec 02, Req 3.1, 4.2, 5.2; design.md «Chat-Agent»,
 * {@code ToolProvider} in Aufgabe 7.2). Immer {@value #DOKUMENTE_SUCHEN}, {@value #DOKUMENT_ZEIGEN} und
 * {@value #FRISTEN}; dazu {@value #KENNZAHL} und {@value #OFFENE_POSTEN} nur mit
 * {@link Funktionen#finanzVerfuegbar()} des Mandanten (Aufgabe 7.1b, Spec 05, Req 8.8); {@code vollstaendigkeit} nur für Rollen, die A3 und A4
 * uneingeschränkt sehen (Aufgabe 7.1c, {@link #vollstaendigkeitErlaubt}; für andere fehlt es in der Liste, Spec 05,
 * Req 5.4). Jede
 * Werkzeugmethode prüft die Auswahl beim Aufruf nochmals, ein nicht angebotenes Werkzeug antwortet nicht (fail closed).
 */
@ApplicationScoped
public class WerkzeugAuswahl {

    public static final String DOKUMENTE_SUCHEN = "dokumente_suchen";
    public static final String DOKUMENT_ZEIGEN = "dokument_zeigen";
    public static final String FRISTEN = "fristen";
    public static final String VOLLSTAENDIGKEIT = "vollstaendigkeit";
    public static final String KENNZAHL = "kennzahl";
    public static final String OFFENE_POSTEN = "offene_posten";
    public static final String WISSEN_INDEX = "wissen_index";
    public static final String WISSEN_SEITE_ZEIGEN = "wissen_seite_zeigen";

    private static final Logger LOG = Logger.getLogger(WerkzeugAuswahl.class);

    private final Predicate<MandantCode> finanzVerfuegbar;

    /**
     * Die Frage nach den Finanzfunktionen läuft im Mandanten des Kontexts in einer eigenen, kurzen Transaktion, weil
     * die Auswahl auch vor der Arbeit des Werkzeugs gefragt wird ({@link Werkzeuglauf}).
     */
    @Inject
    public WerkzeugAuswahl(Funktionen funktionen, MandantKontext mandanten, WerkzeugTransaktion transaktion) {
        this(mandant -> mandanten.mit(mandant, () -> transaktion.neu(funktionen::finanzVerfuegbar)));
    }

    /** Mit eigener Antwort auf «Finanzfunktionen im Mandanten?» (Tests). */
    public WerkzeugAuswahl(Predicate<MandantCode> finanzVerfuegbar) {
        this.finanzVerfuegbar = finanzVerfuegbar;
    }

    /** Namen der angebotenen Werkzeuge in fester Reihenfolge. */
    public List<String> angeboten(WerkzeugKontext kontext) {
        List<String> namen = new ArrayList<>(List.of(DOKUMENTE_SUCHEN, DOKUMENT_ZEIGEN, FRISTEN));
        if (finanz(kontext.mandant())) {
            namen.add(KENNZAHL);
            namen.add(OFFENE_POSTEN);
        }
        if (vollstaendigkeitErlaubt(kontext.sicht())) {
            namen.add(VOLLSTAENDIGKEIT);
        }
        namen.add(WISSEN_INDEX);
        namen.add(WISSEN_SEITE_ZEIGEN);
        return List.copyOf(namen);
    }

    /** Wird {@code werkzeug} für diesen Kontext angeboten? */
    public boolean erlaubt(WerkzeugKontext kontext, String werkzeug) {
        return angeboten(kontext).contains(werkzeug);
    }

    /**
     * Sieht die Rolle A3 und A4 uneingeschränkt ({@code inhaber}, {@code treuhaender}, {@code geschaeftsstelle})?
     * Nur dann gibt es {@code vollstaendigkeit}, damit gefilterte Lücken keine falschen Lücken zeigen (Req 4.2, 5.2).
     */
    public static boolean vollstaendigkeitErlaubt(Sichtregel sicht) {
        return sicht.normal() && sicht.intern() && sicht.personal() == Sichtregel.Personal.ALLE;
    }

    /** Fail closed: Scheitert die Frage, gibt es die Finanzwerkzeuge nicht (kein Wert im Log). */
    private boolean finanz(MandantCode mandant) {
        try {
            return finanzVerfuegbar.test(mandant);
        } catch (RuntimeException e) {
            LOG.errorf("Finanzfunktionen nicht ermittelbar: %s", e.getClass().getName());
            return false;
        }
    }
}
