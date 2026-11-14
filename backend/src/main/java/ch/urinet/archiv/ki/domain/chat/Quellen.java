package ch.urinet.archiv.ki.domain.chat;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Quellen einer Antwort aus den Werkzeugergebnissen des Durchgangs, nie aus dem Modelltext (Requirement 4.1; design.md
 * «Vertrag Welle 7», Quellen): Dokumente aus {@code dokument_zeigen} immer; der Datenstand der Zahlenwerkzeuge immer;
 * Treffer von {@code dokumente_suchen} nur, wenn im Durchgang kein {@code dokument_zeigen} lief, höchstens
 * {@value #MAX_TREFFER}. Ohne Doppelte (Dokumente nach ID), in der Reihenfolge der Aufrufe; andere Werkzeuge liefern
 * keine Quellen.
 */
public final class Quellen {

    public static final String DOKUMENTE_SUCHEN = "dokumente_suchen";
    public static final String DOKUMENT_ZEIGEN = "dokument_zeigen";
    public static final Set<String> ZAHLENWERKZEUGE = Set.of("kennzahl", "offene_posten", "fristen", "vollstaendigkeit");
    public static final int MAX_TREFFER = 5;

    private Quellen() {
    }

    public static List<Quelle> aus(List<ChatEreignis.WerkzeugErgebnis> ergebnisse) {
        boolean gezeigt = ergebnisse.stream().anyMatch(e -> DOKUMENT_ZEIGEN.equals(e.name()));
        Set<Quelle> quellen = new LinkedHashSet<>();
        Set<UUID> dokumente = new HashSet<>();
        int treffer = 0;
        for (ChatEreignis.WerkzeugErgebnis ergebnis : ergebnisse) {
            if (DOKUMENT_ZEIGEN.equals(ergebnis.name())) {
                for (ChatEreignis.QuellDokument d : ergebnis.dokumente()) {
                    if (dokumente.add(d.id())) {
                        quellen.add(new Quelle.Dokument(d.id(), d.titel()));
                    }
                }
            } else if (DOKUMENTE_SUCHEN.equals(ergebnis.name()) && !gezeigt) {
                for (ChatEreignis.QuellDokument d : ergebnis.dokumente()) {
                    if (treffer < MAX_TREFFER && dokumente.add(d.id())) {
                        quellen.add(new Quelle.Dokument(d.id(), d.titel()));
                        treffer++;
                    }
                }
            } else if (ZAHLENWERKZEUGE.contains(ergebnis.name())) {
                ergebnis.datenstand().ifPresent(s -> quellen.add(new Quelle.Stand(ergebnis.name(), s)));
            }
        }
        return List.copyOf(quellen);
    }
}
