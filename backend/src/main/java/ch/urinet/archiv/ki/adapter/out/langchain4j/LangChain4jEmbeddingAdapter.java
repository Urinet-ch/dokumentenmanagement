package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.KiPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter des {@link EmbeddingPort} mit LangChain4j gegen Infomaniak AI Services (Spec 02, Req 2.1): genau ein Request
 * je {@link #einbetten}. Falsche Anzahl oder Dimension der Vektoren, Werte ausserhalb von {@code halfvec} und jeder
 * Fehler sind ein {@link Ausfall}, die Gründe nennen nur Klassennamen (Req 16.7). Ohne Token kein Request
 * ({@link OhneZugang}). Die Frage der Hybridsuche ({@link #einbettenFrage}) geht mit derselben Prüfung über das Modell
 * mit der kurzen Zeitgrenze ({@link EmbeddingModell#suche()}). Nur über {@code EmbeddingAuswahl} wählbar.
 */
@ApplicationScoped
@Typed(LangChain4jEmbeddingAdapter.class)
public class LangChain4jEmbeddingAdapter implements EmbeddingPort {

    /** Grösster Betrag in {@code halfvec} (IEEE 754 binary16); grössere Werte lehnt PostgreSQL ab. */
    static final float HALFVEC_MAX = 65504f;

    private final EmbeddingModell modell;

    public LangChain4jEmbeddingAdapter(EmbeddingModell modell) {
        this.modell = modell;
    }

    @Override
    public Antwort einbetten(List<String> texte) {
        if (texte.isEmpty() || texte.size() > BATCH) {
            throw new IllegalArgumentException("1 bis " + BATCH + " Texte je Aufruf");
        }
        return einbetten(texte, false);
    }

    @Override
    public Antwort einbettenFrage(String frage) {
        return einbetten(List.of(frage), true);
    }

    private Antwort einbetten(List<String> texte, boolean suche) {
        if (!modell.bereit()) {
            return new OhneZugang();
        }
        try {
            EmbeddingModel embeddingModel = suche ? modell.suche() : modell.get();
            Response<List<Embedding>> antwort = embeddingModel.embedAll(texte.stream().map(TextSegment::from).toList());
            KiPort.Verbrauch verbrauch = verbrauch(antwort.tokenUsage());
            List<Embedding> embeddings = antwort.content();
            if (embeddings == null || embeddings.size() != texte.size()) {
                return new Ausfall("falsche Anzahl Vektoren", verbrauch);
            }
            List<float[]> vektoren = new ArrayList<>(embeddings.size());
            for (Embedding embedding : embeddings) {
                float[] vektor = embedding == null ? null : embedding.vector();
                if (vektor == null || vektor.length != DIMENSION) {
                    return new Ausfall("falsche Dimension", verbrauch);
                }
                for (float wert : vektor) {
                    if (!Float.isFinite(wert) || Math.abs(wert) > HALFVEC_MAX) {
                        return new Ausfall("ungültiger Wert", verbrauch);
                    }
                }
                vektoren.add(vektor);
            }
            return new Vektoren(vektoren, verbrauch);
        } catch (RuntimeException e) {
            return new Ausfall(KiFehler.grund(e), verbrauch(null));
        }
    }

    private KiPort.Verbrauch verbrauch(TokenUsage nutzung) {
        long ein = nutzung == null || nutzung.inputTokenCount() == null ? 0 : nutzung.inputTokenCount();
        return new KiPort.Verbrauch(modell.modell(), ein, 0);
    }
}
