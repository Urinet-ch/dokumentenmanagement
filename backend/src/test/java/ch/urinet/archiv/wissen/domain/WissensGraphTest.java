package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WissensGraphTest {

    private static final UUID SEITE_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SEITE_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID SEITE_C = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID DOKUMENT_A = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOKUMENT_B = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID DOKUMENT_C = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @Test
    void huelleLaeuftRekursivMitZyklenOhneDoppelteDokumente() {
        WissensGraph graph = new WissensGraph(
                Map.of(SEITE_A, Set.of(DOKUMENT_A), SEITE_B, Set.of(DOKUMENT_B), SEITE_C, Set.of(DOKUMENT_C)),
                Map.of(SEITE_A, Set.of(SEITE_B), SEITE_B, Set.of(SEITE_C), SEITE_C, Set.of(SEITE_A)),
                Map.of(DOKUMENT_A, WissensStufe.NORMAL, DOKUMENT_B, WissensStufe.INTERN,
                        DOKUMENT_C, WissensStufe.NORMAL),
                Map.of(SEITE_A, WissensStufe.NORMAL, SEITE_B, WissensStufe.NORMAL,
                        SEITE_C, WissensStufe.NORMAL),
                Map.of());

        assertThat(graph.dokumente(SEITE_A)).containsExactlyInAnyOrder(DOKUMENT_A, DOKUMENT_B, DOKUMENT_C);
        assertThat(graph.abhaengigeSeiten(SEITE_B)).containsExactlyInAnyOrder(SEITE_A, SEITE_B, SEITE_C);
        assertThat(graph.wirksameStufe(SEITE_A, graph.dokumente(SEITE_A))).isEqualTo(WissensStufe.INTERN);
    }

    @Test
    void tiefeQuellenketteVerwendetKeinenRekursivenAufrufstack() {
        int tiefe = 10_000;
        List<UUID> seiten = IntStream.range(0, tiefe).mapToObj(ignored -> UUID.randomUUID()).toList();
        UUID dokument = UUID.randomUUID();
        Map<UUID, Set<UUID>> quellen = new HashMap<>();
        Map<UUID, Set<UUID>> direkteDokumente = new HashMap<>();
        Map<UUID, WissensStufe> mindeststufen = new HashMap<>();
        for (int i = 0; i < tiefe; i++) {
            UUID seite = seiten.get(i);
            mindeststufen.put(seite, WissensStufe.NORMAL);
            if (i + 1 < tiefe) {
                quellen.put(seite, Set.of(seiten.get(i + 1)));
            } else {
                direkteDokumente.put(seite, Set.of(dokument));
            }
        }
        WissensGraph graph = new WissensGraph(direkteDokumente, quellen, Map.of(dokument, WissensStufe.NORMAL),
                mindeststufen, Map.of());

        assertThat(graph.dokumente(seiten.getFirst())).containsExactly(dokument);
    }

    @Test
    void mindeststufeWirdBeiDerWirksamenStufeNichtAbgesenkt() {
        WissensGraph graph = new WissensGraph(Map.of(SEITE_A, Set.of(DOKUMENT_A)), Map.of(),
                Map.of(DOKUMENT_A, WissensStufe.NORMAL), Map.of(SEITE_A, WissensStufe.INTERN), Map.of());

        assertThat(graph.wirksameStufe(SEITE_A, graph.dokumente(SEITE_A))).isEqualTo(WissensStufe.INTERN);
    }
}
