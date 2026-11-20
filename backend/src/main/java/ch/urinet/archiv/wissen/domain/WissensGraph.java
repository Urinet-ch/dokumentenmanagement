package ch.urinet.archiv.wissen.domain;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record WissensGraph(Map<UUID, Set<UUID>> dokumenteJeSeite, Map<UUID, Set<UUID>> quellseitenJeSeite,
                           Map<UUID, WissensStufe> dokumentStufen, Map<UUID, WissensStufe> mindeststufen) {

    private static final int MAX_QUELLTIEFE = 128;

    public WissensGraph {
        dokumenteJeSeite = kopieren(dokumenteJeSeite);
        quellseitenJeSeite = kopieren(quellseitenJeSeite);
        dokumentStufen = Map.copyOf(dokumentStufen);
        mindeststufen = Map.copyOf(mindeststufen);
    }

    public boolean kenntSeite(UUID seiteId) {
        return mindeststufen.containsKey(seiteId);
    }

    public Set<UUID> dokumente(UUID seiteId) {
        Set<UUID> besucht = new HashSet<>();
        Set<UUID> dokumente = new HashSet<>();
        ArrayDeque<UUID> offen = new ArrayDeque<>();
        offen.add(seiteId);
        while (!offen.isEmpty()) {
            UUID seite = offen.removeFirst();
            if (!besucht.add(seite)) {
                continue;
            }
            dokumente.addAll(dokumenteJeSeite.getOrDefault(seite, Set.of()));
            offen.addAll(quellseitenJeSeite.getOrDefault(seite, Set.of()));
        }
        return Set.copyOf(dokumente);
    }

    public Set<UUID> abhaengigeSeiten(UUID quellseiteId) {
        Map<UUID, Set<UUID>> rueckwaerts = new HashMap<>();
        quellseitenJeSeite.forEach((seite, quellen) -> quellen.forEach(
                quelle -> rueckwaerts.computeIfAbsent(quelle, ignored -> new HashSet<>()).add(seite)));

        Set<UUID> besucht = new HashSet<>();
        ArrayDeque<UUID> offen = new ArrayDeque<>();
        offen.add(quellseiteId);
        while (!offen.isEmpty()) {
            UUID seite = offen.removeFirst();
            if (besucht.add(seite)) {
                offen.addAll(rueckwaerts.getOrDefault(seite, Set.of()));
            }
        }
        return Set.copyOf(besucht);
    }

    public WissensStufe wirksameStufe(UUID seiteId, Set<UUID> dokumente) {
        WissensStufe stufe = mindeststufeMitQuellen(seiteId);
        for (UUID dokumentId : dokumente) {
            stufe = stufe.hoechste(dokumentStufen.getOrDefault(dokumentId, WissensStufe.INTERN));
        }
        return stufe;
    }

    public WissensStufe mindeststufeMitQuellen(UUID seiteId) {
        return mindeststufen.getOrDefault(seiteId, WissensStufe.NORMAL)
                .hoechste(mindeststufeVonQuellen(quellseitenJeSeite.getOrDefault(seiteId, Set.of())));
    }

    public WissensStufe mindeststufeNachAnnahme(Set<UUID> quellseiten, WissensStufe mindeststufe,
                                               WissensStufe kontextstufe) {
        return mindeststufe.hoechste(kontextstufe).hoechste(mindeststufeVonQuellen(quellseiten));
    }

    /**
     * Wirksame Stufe einer vorgeschlagenen Fassung vor der Annahme (Req 11.1, 6.8): Maximum aus den Dokumenten der
     * Fassung, den Hüllen und Mindeststufen ihrer Quellseiten, der Mindeststufe der Seite und der Kontextstufe der
     * Änderung (11.7). Unbekannte Dokumente und Quellseiten gelten als INTERN.
     */
    public WissensStufe wirksameStufeNachAnnahme(Set<UUID> dokumente, Set<UUID> quellseiten,
                                                 WissensStufe mindeststufe, WissensStufe kontextstufe) {
        WissensStufe stufe = mindeststufeNachAnnahme(quellseiten, mindeststufe, kontextstufe);
        Set<UUID> huelle = new HashSet<>(dokumente);
        for (UUID quellseite : quellseiten) {
            if (!kenntSeite(quellseite)) {
                return WissensStufe.INTERN;
            }
            huelle.addAll(dokumente(quellseite));
        }
        for (UUID dokumentId : huelle) {
            stufe = stufe.hoechste(dokumentStufen.getOrDefault(dokumentId, WissensStufe.INTERN));
        }
        return stufe;
    }

    private WissensStufe mindeststufeVonQuellen(Set<UUID> quellseiten) {
        Set<UUID> besucht = new HashSet<>();
        ArrayDeque<QuellseiteMitTiefe> offen = new ArrayDeque<>();
        quellseiten.forEach(seite -> offen.addLast(new QuellseiteMitTiefe(seite, 1)));
        WissensStufe stufe = WissensStufe.NORMAL;
        while (!offen.isEmpty()) {
            QuellseiteMitTiefe quelle = offen.removeFirst();
            if (!besucht.add(quelle.seiteId())) {
                continue;
            }
            if (quelle.tiefe() > MAX_QUELLTIEFE || !kenntSeite(quelle.seiteId())) {
                return WissensStufe.INTERN;
            }
            stufe = stufe.hoechste(mindeststufen.getOrDefault(quelle.seiteId(), WissensStufe.INTERN));
            if (stufe == WissensStufe.INTERN) {
                return stufe;
            }
            quellseitenJeSeite.getOrDefault(quelle.seiteId(), Set.of())
                    .forEach(seite -> offen.addLast(new QuellseiteMitTiefe(seite, quelle.tiefe() + 1)));
        }
        return stufe;
    }

    private record QuellseiteMitTiefe(UUID seiteId, int tiefe) {
    }

    private static Map<UUID, Set<UUID>> kopieren(Map<UUID, Set<UUID>> quelle) {
        Map<UUID, Set<UUID>> kopie = new HashMap<>();
        quelle.forEach((seite, werte) -> kopie.put(seite, Set.copyOf(werte)));
        return Map.copyOf(kopie);
    }
}
