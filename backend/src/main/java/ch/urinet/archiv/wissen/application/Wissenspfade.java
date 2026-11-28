package ch.urinet.archiv.wissen.application;

import java.util.Arrays;
import java.util.Locale;

/**
 * Welche internen REST-Pfade zur Wissensbasis gehören (Spec 04, Req 22.6): jede Pfadvorlage relativ zu
 * {@code /api/v1} mit einem Abschnitt, der mit {@code wissen} beginnt ({@code /wissen/…}, {@code /wissensbasis/…},
 * {@code /wissensseiten/…}, {@code /wissensentwuerfe/…}, {@code /dokumente/{id}/wissensquelle},
 * {@code /dokumente/{id}/wissen-ingest}), auch künftige. Ausgenommen ist die Webseiten-Schnittstelle unter
 * {@code /public/}: Dort verhält sich der Wissenschat ohne Freischaltung wie bei ausgeschaltetem Schalter (Req 22.8).
 * Geprüft wird die Vorlage aus den {@code @Path}-Angaben, nie der angefragte Pfad, damit Pfad-Parameter nichts
 * auslösen. Gespräche im Modus {@code WISSEN} prüft der Chat selbst, weil der Modus nicht im Pfad steht.
 */
public final class Wissenspfade {

    private Wissenspfade() {
    }

    public static boolean gesperrt(String vorlage) {
        String pfad = vorlage.startsWith("/") ? vorlage : "/" + vorlage;
        if (pfad.equals("/public") || pfad.startsWith("/public/")) {
            return false;
        }
        return Arrays.stream(pfad.split("/"))
                .anyMatch(abschnitt -> abschnitt.toLowerCase(Locale.ROOT).startsWith("wissen"));
    }
}
