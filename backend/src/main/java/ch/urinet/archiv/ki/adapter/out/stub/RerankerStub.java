package ch.urinet.archiv.ki.adapter.out.stub;

import ch.urinet.archiv.ki.application.RerankerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.List;

/**
 * Dev-Stub von {@link RerankerPort}: kein Request, keine Reihenfolge; die Hybridsuche fällt damit auf den Volltext
 * zurück ({@code degradiert}). Aktiv mit {@code archiv.ki.reranker-stub=true} (Profile {@code dev}, {@code lokal}).
 */
@ApplicationScoped
@Typed(RerankerStub.class)
public class RerankerStub implements RerankerPort {

    @Override
    public Antwort sortieren(String frage, List<String> texte, int topN) {
        return new OhneSortierung();
    }
}
