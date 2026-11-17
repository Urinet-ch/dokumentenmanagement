package ch.urinet.archiv.suche.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;

/**
 * Bester Abschnitt eines Dokuments aus dem Vektor-Teil der Hybridsuche (Spec 02, Req 2.2; Spec 03, Req 5.7): Nummer des
 * Abschnitts ({@code chunk.nr}) und Seitenbereich; ohne Seitentrenner sind beide Seiten {@code null}.
 */
public record Fundstelle(int abschnitt, Integer seiteVon, Integer seiteBis) {

    /**
     * Die Seiten für den Chat («2» oder «2-3»), nur plausibel gegen {@code dokument.seiten} ({@link Seitentext#plausibel}):
     * liegt die erste Seite ausserhalb, fehlt die Angabe ganz ({@code null}); liegt nur die letzte ausserhalb, bleibt die
     * erste allein.
     */
    public String seitenText(Integer dokumentSeiten) {
        Integer von = Seitentext.plausibel(seiteVon, dokumentSeiten);
        if (von == null) {
            return null;
        }
        Integer bis = Seitentext.plausibel(seiteBis, dokumentSeiten);
        return bis == null || bis.equals(von) ? von.toString() : von + "-" + bis;
    }
}
