package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Chunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingServiceTest {
    private final List<List<String>> aufrufe = new ArrayList<>();
    private final EmbeddingPort port = new EmbeddingPort() {
        @Override public Antwort einbettenFrage(String frage) { throw new AssertionError("keine Suchfrage"); }
        @Override public Antwort einbetten(List<String> texte) {
            aufrufe.add(List.copyOf(texte));
            return new Vektoren(texte.stream().map(t -> new float[DIMENSION]).toList(),
                    new KiPort.Verbrauch("test", 10, 0));
        }
    };

    @Test
    void prueftBudgetVorJedemBatchUndBuchtDenWissensZweck() throws InterruptedException {
        var zwecke = new ArrayList<String>();
        var budget = new AtomicInteger();
        var service = new EmbeddingService(port, (zweck, verbrauch) -> zwecke.add(zweck));
        var chunks = IntStream.rangeClosed(1, EmbeddingPort.BATCH + 1)
                .mapToObj(i -> new Chunk(i, "Text " + i, null, null)).toList();
        assertThat(service.einbetten(UUID.randomUUID(), chunks, KiVerbrauchPort.WISSEN_EMBEDDING,
                budget::incrementAndGet).orElseThrow()).hasSize(17);
        assertThat(budget.get()).isEqualTo(2);
        assertThat(aufrufe).extracting(List::size).containsExactly(16, 1);
        assertThat(zwecke).containsExactly("WISSEN_EMBEDDING", "WISSEN_EMBEDDING");
    }

    @Test
    void budgetfehlerVerhindertDenNaechstenModellaufruf() {
        var service = new EmbeddingService(port, (zweck, verbrauch) -> { });
        assertThatThrownBy(() -> service.einbetten(UUID.randomUUID(),
                List.of(new Chunk(1, "Text", null, null)), KiVerbrauchPort.WISSEN_EMBEDDING,
                () -> { throw new IllegalStateException("Budget"); })).hasMessage("Budget");
        assertThat(aufrufe).isEmpty();
    }

    @Test
    void fehlendeWissensVerbrauchsbuchungWirdNichtVerschluckt() {
        var service = new EmbeddingService(port,
                (zweck, verbrauch) -> { throw new IllegalStateException("Verbrauch nicht gespeichert"); });
        assertThatThrownBy(() -> service.einbetten(UUID.randomUUID(),
                List.of(new Chunk(1, "Text", null, null)), KiVerbrauchPort.WISSEN_EMBEDDING))
                .hasMessage("Verbrauch nicht gespeichert");
    }
}
