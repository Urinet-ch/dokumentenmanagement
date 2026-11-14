package ch.urinet.archiv.ki.domain.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Frage oder Antwort aus dem Verlauf eines Gesprächs, ohne Werkzeugaufrufe (design.md «Chat-Agent», Gesprächsspeicher). */
public record VerlaufEintrag(ChatRolle rolle, String inhalt) {

    public VerlaufEintrag {
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(inhalt, "inhalt");
    }

    /**
     * Macht aus den letzten Nachrichten eine Folge, die mit einer Frage beginnt und die Rollen abwechselt: führende
     * Antworten entfallen (ihre Frage fiel aus dem Fenster), aufeinanderfolgende Einträge derselben Rolle (eine Antwort
     * ohne Text fehlt) werden mit Leerzeile zusammengefasst. Viele Chat-APIs verlangen das.
     */
    public static List<VerlaufEintrag> normalisieren(List<VerlaufEintrag> eintraege) {
        List<VerlaufEintrag> ergebnis = new ArrayList<>();
        for (VerlaufEintrag e : eintraege) {
            if (ergebnis.isEmpty()) {
                if (e.rolle() == ChatRolle.BENUTZER) {
                    ergebnis.add(e);
                }
                continue;
            }
            VerlaufEintrag letzter = ergebnis.get(ergebnis.size() - 1);
            if (letzter.rolle() == e.rolle()) {
                ergebnis.set(ergebnis.size() - 1, new VerlaufEintrag(e.rolle(), letzter.inhalt() + "\n\n" + e.inhalt()));
            } else {
                ergebnis.add(e);
            }
        }
        return List.copyOf(ergebnis);
    }
}
