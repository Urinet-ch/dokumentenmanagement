package ch.urinet.archiv.wissen.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministische Vorbereitung eines Lint-Laufs. Die Aufrufer laden alle Daten im aktuellen Mandantenschema;
 * vor jedem späteren KI-Aufruf müssen sie Hülle und Dokumentstufen erneut live prüfen.
 */
public final class WissensLint {
    private WissensLint() {
    }

    public static Ergebnis pruefen(List<Seite> seiten, WissensGraph graph,
                                   Map<UUID, WissensPruefung.DokumentQuelle> dokumente,
                                   Map<PaarId, Instant> geprueftePaare, LocalDate heute,
                                   int maxZeichen, int maxPaare) {
        if (maxPaare < 0) {
            throw new IllegalArgumentException("Die Zahl der Paare darf nicht negativ sein.");
        }
        seiten = seiten.stream().sorted(Comparator.comparing(s -> s.fassung().id())).toList();
        Map<UUID, Seite> nachId = seiten.stream()
                .collect(Collectors.toUnmodifiableMap(s -> s.fassung().id(), s -> s));
        Map<String, Seite> nachPfad = seiten.stream()
                .collect(Collectors.toUnmodifiableMap(s -> s.fassung().pfad(), s -> s));
        Set<UUID> ids = nachId.keySet();
        Set<String> pfade = nachPfad.keySet();
        Map<UUID, Set<String>> links = new HashMap<>();
        Map<UUID, Set<UUID>> huellen = new HashMap<>();
        List<Befund> befunde = new ArrayList<>();

        for (Seite eintrag : seiten) {
            Wissensseite seite = eintrag.fassung();
            UUID id = seite.id();
            Set<UUID> huelle = huelle(id, graph, nachId);
            huellen.put(id, huelle);
            Wissensseite.Vertraulichkeit stufe = graph.wirksameStufe(id, huelle) == WissensStufe.NORMAL
                    ? Wissensseite.Vertraulichkeit.NORMAL : Wissensseite.Vertraulichkeit.INTERN;
            var kontext = new WissensPruefung.Kontext(maxZeichen, seite.pfad(), pfade, dokumente, ids, Set.of(),
                    Set.of(), Set.of(), false, false, false, null, stufe);
            WissensPruefung.Ergebnis pruefung = WissensPruefung.pruefen(seite, kontext);
            links.put(id, pruefung.links());
            pruefung.befunde().forEach(b -> befunde.add(new Befund(id, b.code().name(), null)));
            if (seite.veraltetAb() != null && !seite.veraltetAb().isAfter(heute)) {
                befunde.add(new Befund(id, "VERALTET_AB", null));
            }
            if (seite.quellePruefen()) {
                befunde.add(new Befund(id, "QUELLE_PRUEFEN", null));
            }
        }

        Set<UUID> eingehend = new HashSet<>();
        for (Seite eintrag : seiten) {
            Wissensseite seite = eintrag.fassung();
            for (String ziel : links.get(seite.id())) {
                Seite gefunden = nachPfad.get(ziel);
                if (gefunden == null) {
                    befunde.add(new Befund(seite.id(), "LINK_FEHLT", null));
                } else if (!gefunden.fassung().id().equals(seite.id())) {
                    eingehend.add(gefunden.fassung().id());
                }
            }
            for (Quelleneintrag quelle : seite.quellen()) {
                if (quelle.dokumentId() != null) {
                    seiten.stream().map(Seite::fassung)
                            .filter(q -> q.typ() == Seitentyp.QUELLE
                                    && quelle.dokumentId().equals(q.wissensquelleId())
                                    && !q.id().equals(seite.id()))
                            .forEach(q -> eingehend.add(q.id()));
                }
            }
            if (seite.typ() == Seitentyp.QUELLE && links.get(seite.id()).stream()
                    .map(nachPfad::get).noneMatch(ziel -> ziel != null
                            && !ziel.fassung().id().equals(seite.id()))) {
                befunde.add(new Befund(seite.id(), "QUELLE_OHNE_BERUEHRTE_SEITEN", null));
            }
        }
        seiten.stream().map(Seite::fassung)
                .filter(s -> !"ueberblick".equals(s.pfad()) && !eingehend.contains(s.id()))
                .forEach(s -> befunde.add(new Befund(s.id(), "VERWAIST", null)));

        List<Paar> paare = new ArrayList<>();
        for (int i = 0; i < seiten.size(); i++) {
            for (int j = i + 1; j < seiten.size(); j++) {
                Seite a = seiten.get(i);
                Seite b = seiten.get(j);
                Wissensseite erste = a.fassung();
                Wissensseite zweite = b.fassung();
                boolean verlinkt = links.get(erste.id()).contains(zweite.pfad())
                        || links.get(zweite.id()).contains(erste.pfad());
                boolean gemeinsameQuelle = !java.util.Collections.disjoint(
                        huellen.get(erste.id()), huellen.get(zweite.id()));
                Set<String> tags = new HashSet<>(erste.tags());
                tags.retainAll(zweite.tags());
                boolean verwandt = gemeinsameQuelle || tags.size() >= 2;
                if (!verlinkt && verwandt) {
                    befunde.add(new Befund(erste.id(), "QUERVERWEIS_FEHLT", zweite.id()));
                    befunde.add(new Befund(zweite.id(), "QUERVERWEIS_FEHLT", erste.id()));
                }
                if (!(verlinkt || verwandt)) {
                    continue;
                }
                List<VeraltetKandidat> kandidaten = new ArrayList<>();
                veraltet(erste, zweite, links, huellen, dokumente, kandidaten);
                veraltet(zweite, erste, links, huellen, dokumente, kandidaten);
                if (ohnePersonal(huellen.get(erste.id()), dokumente)
                        && ohnePersonal(huellen.get(zweite.id()), dokumente)) {
                    PaarId paarId = new PaarId(erste.id(), zweite.id());
                    paare.add(new Paar(paarId, !verlinkt && verwandt,
                            List.copyOf(kandidaten), geprueftePaare.get(paarId)));
                }
            }
        }
        Comparator<Instant> zeit = Comparator.nullsFirst(Comparator.naturalOrder());
        paare.sort(Comparator.comparing(Paar::geprueftAm, zeit)
                .thenComparing(p -> !(p.querverweisFehlt() || !p.veraltet().isEmpty()))
                .thenComparing(p -> p.id().erste()).thenComparing(p -> p.id().zweite()));
        return new Ergebnis(befunde, paare.stream().limit(maxPaare).toList());
    }

    private static Set<UUID> huelle(UUID id, WissensGraph graph, Map<UUID, Seite> seiten) {
        Set<UUID> dokumente = new HashSet<>(graph.dokumente(id));
        Set<UUID> besucht = new HashSet<>();
        ArrayDeque<UUID> offen = new ArrayDeque<>();
        offen.add(id);
        while (!offen.isEmpty()) {
            UUID aktuell = offen.removeFirst();
            if (besucht.add(aktuell)) {
                Seite seite = seiten.get(aktuell);
                if (seite != null && seite.fassung().wissensquelleId() != null) {
                    dokumente.add(seite.fassung().wissensquelleId());
                }
                offen.addAll(graph.quellseitenJeSeite().getOrDefault(aktuell, Set.of()));
            }
        }
        return Set.copyOf(dokumente);
    }

    private static void veraltet(Wissensseite seite, Wissensseite quelle, Map<UUID, Set<String>> links,
                                  Map<UUID, Set<UUID>> huellen,
                                  Map<UUID, WissensPruefung.DokumentQuelle> dokumente,
                                  List<VeraltetKandidat> kandidaten) {
        if (quelle.typ() != Seitentyp.QUELLE || quelle.wissensquelleId() == null) {
            return;
        }
        var neu = dokumente.get(quelle.wissensquelleId());
        if (neu == null || neu.datum() == null || !neu.archiviert() || !neu.gleicherMandant()) {
            return;
        }
        if (!links.get(quelle.id()).contains(seite.pfad())
                && java.util.Collections.disjoint(huellen.get(quelle.id()), huellen.get(seite.id()))) {
            return;
        }
        for (Quelleneintrag eintrag : seite.quellen()) {
            Set<UUID> referenzen = eintrag.dokumentId() != null ? Set.of(eintrag.dokumentId())
                    : eintrag.quellSeiteId() == null ? Set.of() : huellen.getOrDefault(eintrag.quellSeiteId(), Set.of());
            if (referenzen.stream().map(dokumente::get).anyMatch(alt -> alt != null && alt.datum() != null
                    && alt.datum().isBefore(neu.datum()))) {
                kandidaten.add(new VeraltetKandidat(seite.id(), eintrag.schluessel(), quelle.wissensquelleId()));
            }
        }
    }

    private static boolean ohnePersonal(Set<UUID> huelle, Map<UUID, WissensPruefung.DokumentQuelle> dokumente) {
        return huelle.stream().allMatch(id -> {
            var dokument = dokumente.get(id);
            return dokument != null && dokument.gleicherMandant() && dokument.archiviert() && !dokument.personal();
        });
    }

    public record Seite(Wissensseite fassung) {
    }

    public record Befund(UUID seiteId, String code, UUID bezugId) {
    }

    public record VeraltetKandidat(UUID seiteId, String schluessel, UUID neuereQuelleId) {
    }

    public record PaarId(UUID erste, UUID zweite) {
        public PaarId {
            if (erste.compareTo(zweite) >= 0) {
                throw new IllegalArgumentException("Paar-IDs müssen aufsteigend und verschieden sein.");
            }
        }
    }

    public record Paar(PaarId id, boolean querverweisFehlt, List<VeraltetKandidat> veraltet,
                       Instant geprueftAm) {
        public Paar {
            veraltet = List.copyOf(veraltet);
        }
    }

    public record Ergebnis(List<Befund> befunde, List<Paar> paare) {
        public Ergebnis {
            befunde = List.copyOf(befunde);
            paare = List.copyOf(paare);
        }
    }
}
