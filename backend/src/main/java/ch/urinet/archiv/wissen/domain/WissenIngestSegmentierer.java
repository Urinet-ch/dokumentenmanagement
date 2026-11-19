package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;

import java.util.ArrayList;
import java.util.List;

/** Zerlegt Volltext in begrenzte, mit der PDF-Seite markierte KI-Eingaben. */
public final class WissenIngestSegmentierer {

    private WissenIngestSegmentierer() {
    }

    public static List<Abschnitt> zerlegen(Seitentext text, int von, int bis, int maxZeichen) {
        if (text == null || von < 1 || bis < von || bis > text.anzahl() || maxZeichen < 1) {
            throw new IllegalArgumentException("Ungültiger Seitenbereich oder Abschnittsgrenze");
        }
        List<Abschnitt> abschnitte = new ArrayList<>();
        for (int seite = von; seite <= bis; seite++) {
            String inhalt = text.seiten().get(seite - 1);
            for (String teil : teilen(inhalt, maxZeichen)) {
                if (!teil.isBlank()) {
                    abschnitte.add(new Abschnitt(abschnitte.size(), seite, "[Seite " + seite + "]\n" + teil));
                }
            }
        }
        return List.copyOf(abschnitte);
    }

    private static List<String> teilen(String text, int maxZeichen) {
        List<String> teile = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int codepoints = Math.min(maxZeichen, text.codePointCount(start, text.length()));
            int ende = text.offsetByCodePoints(start, codepoints);
            if (ende < text.length()) {
                int zeilenende = text.lastIndexOf('\n', ende - 1);
                if (zeilenende > start + maxZeichen / 2) {
                    ende = zeilenende;
                }
            }
            String teil = text.substring(start, ende).strip();
            if (!teil.isEmpty()) {
                teile.add(teil);
            }
            start = ende;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return teile;
    }

    public record Abschnitt(int nummer, int seite, String text) {
    }
}
