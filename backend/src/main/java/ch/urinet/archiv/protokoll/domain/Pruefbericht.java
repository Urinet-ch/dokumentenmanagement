package ch.urinet.archiv.protokoll.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Ergebnis des Prüfwerkzeugs (Req 15.12): Fehler (z. B. Signatur, Lücke in der Kette, kein gültiger Zeitstempel) und
 * Meldungen (fehlender Zeitstempel eines Dienstes, Verspätung). Texte nennen nur Datum, Dienst und Teil, nie Inhalte.
 */
public final class Pruefbericht {

    private final List<String> fehler = new ArrayList<>();
    private final List<String> meldungen = new ArrayList<>();
    private int geprueft;

    public void fehler(String text) {
        fehler.add(text);
    }

    public void meldung(String text) {
        meldungen.add(text);
    }

    public void geprueft() {
        geprueft++;
    }

    public List<String> fehler() {
        return List.copyOf(fehler);
    }

    public List<String> meldungen() {
        return List.copyOf(meldungen);
    }

    public int anzahlGeprueft() {
        return geprueft;
    }

    public boolean ok() {
        return fehler.isEmpty();
    }
}
