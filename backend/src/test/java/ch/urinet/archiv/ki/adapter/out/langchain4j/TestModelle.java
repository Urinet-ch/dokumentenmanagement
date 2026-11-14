package ch.urinet.archiv.ki.adapter.out.langchain4j;

import java.util.Optional;

/** Modelle ohne Quarkus für JUnit-Tests anderer Adapter (z. B. Token für den Reranker). */
public final class TestModelle {

    private TestModelle() {
    }

    /** Embedding-Modell mit Basis-URL und dem Inhalt der Secret-Datei {@code infomaniak_ai_token}. */
    public static EmbeddingModell embedding(String basisUrl, Optional<String> token) {
        EmbeddingModell modell = new EmbeddingModell();
        modell.basisUrl = basisUrl;
        modell.modell = "bge_multilingual_gemma2";
        modell.token = token;
        return modell;
    }
}
