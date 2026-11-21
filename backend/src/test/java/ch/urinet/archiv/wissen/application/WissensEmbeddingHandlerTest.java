package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.EmbeddingService;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.domain.Chunker;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissensEmbeddingHandlerTest {
    private final UUID id = UUID.randomUUID();
    private final Speicher speichern = new Speicher();
    private final Modell modell = new Modell();
    private final List<String> zwecke = new ArrayList<>();
    private final WissensEmbeddingHandler handler = new WissensEmbeddingHandler(speichern,
            new EmbeddingService(modell, (zweck, verbrauch) -> zwecke.add(zweck)),
            new WissenModellBudget(null, null, true, true));

    @Test
    void freigegebeneFassungErhaeltAbschnitteMitTitelTypUndWissensVerbrauch() throws InterruptedException {
        handler.ausfuehren(auftrag(1));
        assertThat(modell.aufrufe).isEqualTo(1);
        assertThat(speichern.geschrieben).hasSize(1);
        assertThat(speichern.geschrieben.getFirst().chunk().text()).contains("Titel", "Konzept", "Inhalt");
        assertThat(speichern.geschrieben.getFirst().vektor()).hasSize(EmbeddingPort.DIMENSION);
        assertThat(zwecke).containsExactly("WISSEN_EMBEDDING");
    }

    @Test
    void ueberholterAuftragRuftKeinModellAufUndSchreibtNichts() throws InterruptedException {
        handler.ausfuehren(auftrag(2));
        assertThat(modell.aufrufe).isZero();
        assertThat(speichern.geschrieben).isEmpty();
        assertThat(zwecke).isEmpty();
    }

    @Test
    void stubErzeugtKeineErfundenenVektorenUndKeinenVerbrauch() throws InterruptedException {
        modell.stub = true;
        handler.ausfuehren(auftrag(1));
        assertThat(speichern.geschrieben).hasSize(1);
        assertThat(speichern.geschrieben.getFirst().vektor()).isNull();
        assertThat(zwecke).isEmpty();
    }

    @Test
    void unterbrechungNachModellantwortVerhindertDasSchreiben() {
        modell.unterbrechen = true;
        try {
            assertThatThrownBy(() -> handler.ausfuehren(auftrag(1))).isInstanceOf(InterruptedException.class);
            assertThat(speichern.geschrieben).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    private Auftrag auftrag(int version) {
        return new Auftrag(1, new MandantCode("urinet"), WissensEmbeddingSpeichern.EINZELN,
                new Nutzlast(Map.of("seiteId", id, "version", version)), 0);
    }

    private class Speicher extends WissensEmbeddingSpeichern {
        List<ChunkPort.Eingebettet> geschrieben = List.of();
        Speicher() { super(null, null); }
        @Override public Optional<WissensEmbeddingPort.Stand> laden(UUID seiteId) {
            assertThat(seiteId).isEqualTo(id);
            return Optional.of(new WissensEmbeddingPort.Stand(id, 1,
                    new Chunker.Quelle("Titel", "Konzept", null, "Inhalt", null, true)));
        }
        @Override public boolean ersetzen(Auftrag auftrag, UUID seiteId, int version,
                                         List<ChunkPort.Eingebettet> chunks) {
            assertThat(seiteId).isEqualTo(id);
            assertThat(version).isEqualTo(1);
            geschrieben = List.copyOf(chunks);
            return true;
        }
    }

    private static class Modell implements EmbeddingPort {
        boolean stub, unterbrechen;
        int aufrufe;
        @Override public Antwort einbettenFrage(String frage) { throw new AssertionError("keine Suchfrage"); }
        @Override public Antwort einbetten(List<String> texte) {
            aufrufe++;
            if (stub) return new OhneVektoren();
            if (unterbrechen) Thread.currentThread().interrupt();
            return new Vektoren(texte.stream().map(t -> new float[DIMENSION]).toList(),
                    new KiPort.Verbrauch("test", 10, 0));
        }
    }
}
