package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.domain.Ergebnis;

import java.util.Map;

/** Zellentexte der Rechtematrix in requirements.md (Req 2.6); ein unbekannter Text ist ein Fehler. */
final class RechtematrixZelle {

    private static final Map<String, Ergebnis> ZELLEN = Map.of(
            "erlaubt", Ergebnis.ERLAUBT,
            "403", Ergebnis.VERBOTEN,
            "404", Ergebnis.NICHT_SICHTBAR,
            "nur eigene ²", Ergebnis.NUR_EIGENE,
            "erlaubt (nur lesend)", Ergebnis.NUR_LESEND,
            "nur selbst erfasste", Ergebnis.NUR_SELBST_ERFASSTE);

    private RechtematrixZelle() {
    }

    static Ergebnis ergebnis(String text) {
        Ergebnis ergebnis = ZELLEN.get(text.strip());
        if (ergebnis == null) {
            throw new IllegalArgumentException("Unbekannter Zellentext in der Rechtematrix: «" + text + "»");
        }
        return ergebnis;
    }
}
