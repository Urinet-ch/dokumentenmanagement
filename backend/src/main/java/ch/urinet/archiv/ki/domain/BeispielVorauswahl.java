package ch.urinet.archiv.ki.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Vorauswahl der Korrekturbeispiele vor dem KI-Aufruf (Req 7.9, design.md «KI», {@code BeispielVorauswahl}),
 * deterministisch und ohne zweiten KI-Aufruf: Beispiele mit mindestens einem gemeinsamen Merkmal, sortiert nach Anzahl
 * übereinstimmender Merkmale, dann nach Zeit absteigend (bei gleicher Zeit die höhere ID zuerst), höchstens
 * {@value #ANZAHL}; ohne Treffer die {@value #ANZAHL} neuesten. Nur Beispiele, die der Erfasser lesen darf
 * ({@link BeispielSicht}).
 */
public final class BeispielVorauswahl {

    public static final int ANZAHL = 5;

    private static final Comparator<Beispiel> NEUESTE = Comparator.comparing(Beispiel::zeit).reversed()
            .thenComparing(Comparator.comparingLong(Beispiel::id).reversed());

    private BeispielVorauswahl() {
    }

    /**
     * @param beleg      Merkmale des Belegs
     * @param kandidaten Beispiele mit mindestens einem gemeinsamen Merkmal (aus der Datenbank; geprüft wird hier erneut)
     * @param neueste    die neuesten Beispiele für den Fall ohne Treffer
     */
    public static List<Beispiel> auswaehlen(Merkmale beleg, List<Beispiel> kandidaten, List<Beispiel> neueste,
                                            BeispielSicht sicht) {
        List<Beispiel> treffer = kandidaten.stream()
                .filter(sicht::erlaubt)
                .filter(b -> beleg.treffer(b.merkmale()) > 0)
                .sorted(Comparator.comparingInt((Beispiel b) -> beleg.treffer(b.merkmale())).reversed()
                        .thenComparing(NEUESTE))
                .limit(ANZAHL)
                .toList();
        if (!treffer.isEmpty()) {
            return treffer;
        }
        return neueste.stream().filter(sicht::erlaubt).sorted(NEUESTE).limit(ANZAHL).toList();
    }
}
