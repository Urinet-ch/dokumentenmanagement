package ch.urinet.archiv.gemeinsam.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PDF-Seiten aus {@code dokument.volltext} (Spec 04, design.md, Seitentext; Spec 03, Req 5.7): {@code pdftotext}
 * schliesst jede Seite mit dem Seitentrenner {@code \f} ab. Ohne Trenner (z. B. Tika-Text bei {@code pdfa_fehlt}) gibt
 * es keine Seiten. Seiten sind 1-basiert.
 */
public record Seitentext(List<String> seiten) {

    public static final char TRENNER = '\f';

    public Seitentext {
        seiten = List.copyOf(seiten);
        if (seiten.isEmpty()) {
            throw new IllegalArgumentException("Seitentext ohne Seiten");
        }
    }

    /**
     * Seiten des Volltexts; leer ohne Trenner. Der Trenner nach der letzten Seite ergibt keine zusätzliche Seite, eine
     * leere Seite mitten im Text bleibt (Seitennummern stimmen dann mit der PDF/A überein).
     */
    public static Optional<Seitentext> von(String volltext) {
        if (volltext == null || volltext.indexOf(TRENNER) < 0) {
            return Optional.empty();
        }
        List<String> teile = new ArrayList<>(Arrays.asList(volltext.split(String.valueOf(TRENNER), -1)));
        if (teile.size() > 1 && teile.getLast().isBlank()) {
            teile.removeLast();
        }
        return Optional.of(new Seitentext(teile));
    }

    /**
     * Seiten nur, wenn der Volltext Trenner hat und ihre Zahl {@code erwartet} entspricht (Spec 03, Req 5.7: sonst ohne
     * Seitenbereich); {@code erwartet} ist {@code dokument.seiten}.
     */
    public static Optional<Seitentext> von(String volltext, Integer erwartet) {
        return von(volltext).filter(text -> erwartet != null && text.anzahl() == erwartet);
    }

    public int anzahl() {
        return seiten.size();
    }
}
