package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.application.WissenIngestPort.Lauf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten;
import ch.urinet.archiv.wissen.application.WissenIngestSpeichern.Stand;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenIngestSpeichernTest {

    @Test
    void internerIngestUebergibtUeberblickNichtImIndex() {
        SeitenMetadaten ueberblick = seite("ueberblick");
        SeitenMetadaten intern = seite("konzepte/intern");
        Speicher speichern = new Speicher(stand("INTERN", List.of(ueberblick, intern)),
                List.of(ueberblick, intern));

        assertThat(speichern.indexLaden(UUID.randomUUID(), "Frist"))
                .extracting(SeitenMetadaten::pfad)
                .containsExactly("konzepte/intern");
    }

    @Test
    void normalerIngestUebergibtUeberblickImIndex() {
        SeitenMetadaten ueberblick = seite("ueberblick");
        Speicher speichern = new Speicher(stand("NORMAL", List.of(ueberblick)), List.of(ueberblick));

        assertThat(speichern.indexLaden(UUID.randomUUID(), "Frist"))
                .extracting(SeitenMetadaten::pfad)
                .containsExactly("ueberblick");
    }

    private static SeitenMetadaten seite(String pfad) {
        return new SeitenMetadaten(UUID.randomUUID(), pfad, "Konzept", pfad, "Beschreibung", List.of(),
                "FREIGEGEBEN", 1, "NORMAL");
    }

    private static Stand stand(String vertraulichkeit, List<SeitenMetadaten> seiten) {
        Lauf lauf = new Lauf(UUID.randomUUID(), UUID.randomUUID(), "IN_ARBEIT", "ARCHIVIERT", "WISSENSQUELLE",
                vertraulichkeit, true, "Testquelle", LocalDate.of(2026, 4, 30), "Text\f", 1, true,
                null, null, "PLANUNG", 0, 0, "test-modell", false, 1_000_000, 1_000_000,
                WissenIngestPort.Fortschritt.leer());
        WissensStufe stufe = "NORMAL".equals(vertraulichkeit) ? WissensStufe.NORMAL : WissensStufe.INTERN;
        return new Stand(lauf, new WissensGraph(Map.of(), Map.of(), Map.of(), Map.of()), seiten, Set.of(),
                Map.of(), Map.of(), 0, stufe);
    }

    private static final class Speicher extends WissenIngestSpeichern {
        private final Stand stand;
        private final List<SeitenMetadaten> sichtbar;

        private Speicher(Stand stand, List<SeitenMetadaten> sichtbar) {
            super(null, null, new WissenKonfiguration("test-modell", 30_000, 15, 60_000,
                    20_000, 12_000, 300_000, 1_000_000), null);
            this.stand = stand;
            this.sichtbar = sichtbar;
        }

        @Override
        public Optional<Stand> laden(UUID entwurfId, Set<UUID> seitenIds) {
            return Optional.of(stand);
        }

        @Override
        public List<SeitenMetadaten> sichtbareSeiten(Stand stand) {
            return sichtbar;
        }
    }
}
