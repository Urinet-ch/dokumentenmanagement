package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Welche Werkzeuge der Agent für eine Chat-Anfrage angeboten bekommt (Spec 02, Req 3.1, 4.2, 5.2; design.md «Chat-Agent»,
 * {@code ToolProvider} in Aufgabe 7.2). Heute immer {@value #DOKUMENTE_SUCHEN}, {@value #DOKUMENT_ZEIGEN} und
 * {@value #FRISTEN}. Erweiterungspunkte: {@code kennzahl} und {@code offene_posten} nur mit
 * {@code Funktionen.finanzVerfuegbar()} des Mandanten (Aufgabe 7.1b, Spec 05, Req 8.8), {@code vollstaendigkeit} nur
 * für Rollen, die A3 und A4 uneingeschränkt sehen (Aufgabe 7.1c, {@link #vollstaendigkeitErlaubt}). Jede
 * Werkzeugmethode prüft die Auswahl beim Aufruf nochmals, ein nicht angebotenes Werkzeug antwortet nicht (fail closed).
 */
@ApplicationScoped
public class WerkzeugAuswahl {

    public static final String DOKUMENTE_SUCHEN = "dokumente_suchen";
    public static final String DOKUMENT_ZEIGEN = "dokument_zeigen";
    public static final String FRISTEN = "fristen";

    /** Namen der angebotenen Werkzeuge in fester Reihenfolge. */
    public List<String> angeboten(WerkzeugKontext kontext) {
        // 7.1b ergänzt kennzahl und offene_posten, wenn Funktionen.finanzVerfuegbar() für kontext.mandant() gilt;
        // 7.1c ergänzt vollstaendigkeit, wenn vollstaendigkeitErlaubt(kontext.sicht())
        return List.of(DOKUMENTE_SUCHEN, DOKUMENT_ZEIGEN, FRISTEN);
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
}
