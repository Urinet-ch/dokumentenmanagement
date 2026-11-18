package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenHuelleDienstTest {

    private static final UUID SEITE = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID QUELLE = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000403");

    @Test
    void freigabeSchreibtRekursiveHuelleJeVersionUndErhaeltMindeststufe() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(QUELLE, Set.of(DOKUMENT)),
                Map.of(SEITE, Set.of(QUELLE)),
                Map.of(DOKUMENT, WissensStufe.NORMAL),
                Map.of(SEITE, WissensStufe.INTERN, QUELLE, WissensStufe.NORMAL),
                Map.of(SEITE, "konzepte/arbeit", QUELLE, "quellen/2026-03-test")));

        Set<UUID> huelle = new WissenHuelleDienst(repository).versionFreigeben(SEITE, 2);

        assertThat(huelle).containsExactly(DOKUMENT);
        assertThat(repository.aktuelle).containsEntry(SEITE, Set.of(DOKUMENT));
        assertThat(repository.stufen).containsEntry(SEITE, WissensStufe.INTERN);
        assertThat(repository.versionen).containsEntry(SEITE, Set.of(DOKUMENT));
    }

    @Test
    void ueberblickBleibtNormalAuchWennEineHuelleHoeherWaere() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)), Map.of(),
                Map.of(DOKUMENT, WissensStufe.INTERN), Map.of(SEITE, WissensStufe.NORMAL),
                Map.of(SEITE, "ueberblick")));

        new WissenHuelleDienst(repository).versionFreigeben(SEITE, 1);

        assertThat(repository.aktuelle).containsEntry(SEITE, Set.of(DOKUMENT));
        assertThat(repository.stufen).doesNotContainKey(SEITE);
        assertThat(repository.versionen).containsEntry(SEITE, Set.of(DOKUMENT));
    }

    @Test
    void dokumentaenderungBerechnetAlleSeitenNeuOhneVersionenZuAendern() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)), Map.of(),
                Map.of(DOKUMENT, WissensStufe.INTERN), Map.of(SEITE, WissensStufe.NORMAL),
                Map.of(SEITE, "konzepte/arbeit")));
        repository.mitDokument.add(SEITE);

        Set<UUID> betroffen = new WissenHuelleDienst(repository).dokumentSichtbarkeitGeaendert(DOKUMENT);

        assertThat(betroffen).containsExactly(SEITE);
        assertThat(repository.stufen).containsEntry(SEITE, WissensStufe.INTERN);
        assertThat(repository.versionen).isEmpty();
    }

    private static final class TestRepository implements WissenHuelleRepository {

        private final WissensGraph graph;
        private final Map<UUID, Set<UUID>> aktuelle = new HashMap<>();
        private final Map<UUID, WissensStufe> stufen = new HashMap<>();
        private final Map<UUID, Set<UUID>> versionen = new HashMap<>();
        private final Set<UUID> mitDokument = new HashSet<>();

        private TestRepository(WissensGraph graph) {
            this.graph = graph;
        }

        @Override
        public WissensGraph graphLaden() {
            return graph;
        }

        @Override
        public Set<UUID> seitenMitDokument(UUID dokumentId) {
            return mitDokument;
        }

        @Override
        public void aktuelleHuelleSetzen(UUID seiteId, Set<UUID> dokumente) {
            aktuelle.put(seiteId, dokumente);
        }

        @Override
        public void vertraulichkeitSetzen(UUID seiteId, WissensStufe stufe) {
            stufen.put(seiteId, stufe);
        }

        @Override
        public void versionsHuelleEinfuegen(UUID seiteId, int version, Set<UUID> dokumente) {
            versionen.put(seiteId, dokumente);
        }
    }
}
