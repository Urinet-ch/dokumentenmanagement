package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenHuelleDienstTest {

    private static final UUID SEITE = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID QUELLE = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID ZITIERENDE = UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Test
    void freigabeSchreibtRekursiveHuelleJeVersionUndErhaeltMindeststufe() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(QUELLE, Set.of(DOKUMENT)),
                Map.of(SEITE, Set.of(QUELLE)),
                Map.of(DOKUMENT, WissensStufe.NORMAL),
                Map.of(SEITE, WissensStufe.INTERN, QUELLE, WissensStufe.NORMAL)));

        Set<UUID> huelle = new WissenHuelleDienst(repository).versionFreigeben(SEITE, 2);

        assertThat(huelle).containsExactly(DOKUMENT);
        assertThat(repository.aktuelle).containsEntry(SEITE, Set.of(DOKUMENT));
        assertThat(repository.stufen).containsEntry(SEITE, WissensStufe.INTERN);
        assertThat(repository.mindeststufen).containsEntry(SEITE, WissensStufe.INTERN);
        assertThat(repository.versionen).containsEntry(SEITE, Set.of(DOKUMENT));
    }

    @Test
    void ueberblickSpeichertDieWirksameStufeAuchWennSieUeberNormalSteigt() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)), Map.of(),
                Map.of(DOKUMENT, WissensStufe.INTERN), Map.of(SEITE, WissensStufe.NORMAL)));

        new WissenHuelleDienst(repository).versionFreigeben(SEITE, 1);

        assertThat(repository.aktuelle).containsEntry(SEITE, Set.of(DOKUMENT));
        assertThat(repository.stufen).containsEntry(SEITE, WissensStufe.INTERN);
        assertThat(repository.versionen).containsEntry(SEITE, Set.of(DOKUMENT));
    }

    @Test
    void neuberechnungSperrtDenMandantenBevorSieDenGraphenLiest() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)), Map.of(),
                Map.of(DOKUMENT, WissensStufe.NORMAL), Map.of(SEITE, WissensStufe.NORMAL)));
        repository.mitDokument.add(SEITE);

        WissenHuelleDienst dienst = new WissenHuelleDienst(repository);
        dienst.versionFreigeben(SEITE, 1);
        dienst.dokumentSichtbarkeitGeaendert(DOKUMENT);

        assertThat(repository.aufrufe).containsExactly("sperren", "graph", "sperren", "seiten", "graph");
    }

    @Test
    void dokumentaenderungBerechnetAlleSeitenNeuOhneVersionenZuAendern() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)), Map.of(),
                Map.of(DOKUMENT, WissensStufe.INTERN), Map.of(SEITE, WissensStufe.NORMAL)));
        repository.mitDokument.add(SEITE);

        Set<UUID> betroffen = new WissenHuelleDienst(repository).dokumentSichtbarkeitGeaendert(DOKUMENT);

        assertThat(betroffen).containsExactly(SEITE);
        assertThat(repository.stufen).containsEntry(SEITE, WissensStufe.INTERN);
        assertThat(repository.versionen).isEmpty();
    }

    @Test
    void freigabeBerechnetAuchSeitenNeuDieDieFreigegebeneSeiteZitieren() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)),
                Map.of(ZITIERENDE, Set.of(SEITE)),
                Map.of(DOKUMENT, WissensStufe.INTERN),
                Map.of(SEITE, WissensStufe.NORMAL, ZITIERENDE, WissensStufe.NORMAL)));

        new WissenHuelleDienst(repository).versionFreigeben(SEITE, 1);

        assertThat(repository.aktuelle).containsEntry(ZITIERENDE, Set.of(DOKUMENT));
        assertThat(repository.stufen).containsEntry(ZITIERENDE, WissensStufe.INTERN);
        assertThat(repository.versionen).containsOnlyKeys(SEITE);
    }

    @Test
    void zitatierteMindeststufeErhoehtAktuelleSeiteVersionUndAbhaengigeSeiten() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(QUELLE, Set.of(DOKUMENT)),
                Map.of(SEITE, Set.of(QUELLE), ZITIERENDE, Set.of(SEITE)),
                Map.of(DOKUMENT, WissensStufe.NORMAL),
                Map.of(QUELLE, WissensStufe.INTERN, SEITE, WissensStufe.NORMAL,
                        ZITIERENDE, WissensStufe.NORMAL)));

        new WissenHuelleDienst(repository).versionFreigeben(SEITE, 2);

        assertThat(repository.mindeststufen).containsEntry(SEITE, WissensStufe.INTERN)
                .containsEntry(ZITIERENDE, WissensStufe.INTERN);
        assertThat(repository.stufen).containsEntry(SEITE, WissensStufe.INTERN)
                .containsEntry(ZITIERENDE, WissensStufe.INTERN);
    }

    @Test
    void neuberechnungSenktEineBereitsGeerbteMindeststufeNichtAb() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(QUELLE, Set.of(DOKUMENT)),
                Map.of(SEITE, Set.of(QUELLE), ZITIERENDE, Set.of(SEITE)),
                Map.of(DOKUMENT, WissensStufe.NORMAL),
                Map.of(QUELLE, WissensStufe.NORMAL, SEITE, WissensStufe.NORMAL,
                        ZITIERENDE, WissensStufe.INTERN)));

        new WissenHuelleDienst(repository).versionFreigeben(SEITE, 1);

        assertThat(repository.mindeststufen).containsEntry(ZITIERENDE, WissensStufe.INTERN);
    }

    @Test
    void sperrenVorDerFreigabeNimmtDieMandantensperreVorDemGraphen() {
        TestRepository repository = new TestRepository(new WissensGraph(
                Map.of(SEITE, Set.of(DOKUMENT)), Map.of(),
                Map.of(DOKUMENT, WissensStufe.NORMAL), Map.of(SEITE, WissensStufe.NORMAL)));

        WissenHuelleDienst dienst = new WissenHuelleDienst(repository);
        dienst.sperren();
        dienst.versionFreigeben(SEITE, 1);

        assertThat(repository.aufrufe).containsExactly("sperren", "sperren", "graph");
    }

    private static final class TestRepository implements WissenHuelleRepository {

        private final WissensGraph graph;
        private final Map<UUID, Set<UUID>> aktuelle = new HashMap<>();
        private final Map<UUID, WissensStufe> stufen = new HashMap<>();
        private final Map<UUID, WissensStufe> mindeststufen = new HashMap<>();
        private final Map<UUID, Set<UUID>> versionen = new HashMap<>();
        private final Set<UUID> mitDokument = new HashSet<>();
        private final List<String> aufrufe = new ArrayList<>();

        private TestRepository(WissensGraph graph) {
            this.graph = graph;
        }

        @Override
        public void sperren() {
            aufrufe.add("sperren");
        }

        @Override
        public WissensGraph graphLaden() {
            aufrufe.add("graph");
            return graph;
        }

        @Override
        public WissensGraph graphLaden(Set<UUID> weitereDokumente) {
            return graphLaden();
        }

        @Override
        public Set<UUID> seitenMitDokument(UUID dokumentId) {
            aufrufe.add("seiten");
            return mitDokument;
        }

        @Override
        public void aktuelleHuelleSetzen(UUID seiteId, Set<UUID> dokumente) {
            aktuelle.put(seiteId, dokumente);
        }

        @Override
        public void mindeststufeAnheben(UUID seiteId, WissensStufe mindeststufe) {
            // wie das SQL im Produktivcode: nie senken
            mindeststufen.merge(seiteId, mindeststufe, (alt, neu) ->
                    alt == WissensStufe.INTERN || neu == WissensStufe.INTERN ? WissensStufe.INTERN : WissensStufe.NORMAL);
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
