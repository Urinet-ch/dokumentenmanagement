package ch.urinet.archiv.ki.adapter.out.stub;

import ch.urinet.archiv.ki.application.EmbeddingPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.List;

/**
 * Dev-Stub von {@link EmbeddingPort}: kein Request, keine Vektoren; die Abschnitte entstehen trotzdem (ohne Embedding).
 * Aktiv mit {@code archiv.ki.embedding-stub=true} (Profile {@code dev} über {@code archiv.ki.stub}, {@code lokal}).
 */
@ApplicationScoped
@Typed(EmbeddingStub.class)
public class EmbeddingStub implements EmbeddingPort {

    @Override
    public Antwort einbetten(List<String> texte) {
        return new OhneVektoren();
    }
}
