package ch.urinet.archiv.wissen.domain;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record WissensGraph(Map<UUID, Set<UUID>> dokumenteJeSeite, Map<UUID, Set<UUID>> quellseitenJeSeite,
                           Map<UUID, WissensStufe> dokumentStufen, Map<UUID, WissensStufe> mindeststufen) {

    static final int MAX_QUELLTIEFE = 128;

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
        WissensStufe stufe = wirksameMindeststufe(seiteId);
        for (UUID dokumentId : dokumente) {
            stufe = stufe.hoechste(dokumentStufen.getOrDefault(dokumentId, WissensStufe.INTERN));
        }
        return stufe;
    }

    public WissensStufe wirksameMindeststufe(UUID seiteId) {
        QuellenAufloesung aufloesung = zitierteSeiten(Set.of(seiteId), 0);
        if (aufloesung.failClosed()) {
            return WissensStufe.INTERN;
        }
        WissensStufe stufe = WissensStufe.NORMAL;
        for (UUID quelle : aufloesung.seiten()) {
            stufe = stufe.hoechste(mindeststufen.getOrDefault(quelle, WissensStufe.INTERN));
        }
        return stufe;
    }

    /**
     * Wirksame Stufe einer vorgeschlagenen Fassung vor der Annahme (Req 11.1, 6.8): Maximum aus den Dokumenten der
     * Fassung, den Hüllen ihrer Quellseiten, der Mindeststufe der Seite und der Kontextstufe der Änderung (11.7).
     * Unbekannte Dokumente und Quellseiten gelten als INTERN.
     */
    public WissensStufe wirksameStufeNachAnnahme(Set<UUID> dokumente, Set<UUID> quellseiten,
                                                 WissensStufe mindeststufe, WissensStufe kontextstufe) {
        WissensStufe stufe = mindeststufe.hoechste(kontextstufe);
        Set<UUID> huelle = new HashSet<>(dokumente);
        QuellenAufloesung aufloesung = zitierteSeiten(quellseiten, 1);
        if (aufloesung.failClosed()) {
            return WissensStufe.INTERN;
        }
        for (UUID quellseite : aufloesung.seiten()) {
            stufe = stufe.hoechste(mindeststufen.getOrDefault(quellseite, WissensStufe.INTERN));
            huelle.addAll(dokumenteJeSeite.getOrDefault(quellseite, Set.of()));
        }
        for (UUID dokumentId : huelle) {
            stufe = stufe.hoechste(dokumentStufen.getOrDefault(dokumentId, WissensStufe.INTERN));
        }
        return stufe;
    }

    private QuellenAufloesung zitierteSeiten(Set<UUID> startseiten, int starttiefe) {
        Map<UUID, Integer> tiefen = new HashMap<>();
        ArrayDeque<UUID> offen = new ArrayDeque<>();
        for (UUID seiteId : startseiten) {
            if (!kenntSeite(seiteId) || starttiefe > MAX_QUELLTIEFE) {
                return new QuellenAufloesung(Set.of(), true);
            }
            if (tiefen.putIfAbsent(seiteId, starttiefe) == null) {
                offen.addLast(seiteId);
            }
        }

        while (!offen.isEmpty()) {
            UUID seiteId = offen.removeFirst();
            int naechsteTiefe = tiefen.get(seiteId) + 1;
            for (UUID quellseite : quellseitenJeSeite.getOrDefault(seiteId, Set.of())) {
                if (!kenntSeite(quellseite)) {
                    return new QuellenAufloesung(Set.of(), true);
                }
                if (tiefen.containsKey(quellseite)) {
                    continue;
                }
                if (naechsteTiefe > MAX_QUELLTIEFE) {
                    return new QuellenAufloesung(Set.of(), true);
                }
                tiefen.put(quellseite, naechsteTiefe);
                offen.addLast(quellseite);
            }
        }
        return new QuellenAufloesung(Set.copyOf(tiefen.keySet()), false);
    }

    private record QuellenAufloesung(Set<UUID> seiten, boolean failClosed) {}

    private static Map<UUID, Set<UUID>> kopieren(Map<UUID, Set<UUID>> quelle) {
        Map<UUID, Set<UUID>> kopie = new HashMap<>();
        quelle.forEach((seite, werte) -> kopie.put(seite, Set.copyOf(werte)));
        return Map.copyOf(kopie);
    }
}
