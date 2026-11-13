package ch.urinet.archiv.ki.application;

import java.util.List;

/**
 * Driven Port für Embeddings (Spec 02, Req 2.1; design.md «Embeddings und Hybridsuche»): je Text ein Vektor des Modells
 * {@code archiv.ki.embedding-modell} bei Infomaniak AI Services (Spike 0.3: {@code bge_multilingual_gemma2},
 * {@link #DIMENSION} Dimensionen, nicht normiert, darum Kosinus). Höchstens {@link #BATCH} Texte je Aufruf, genau ein
 * Request je Aufruf. Adapter oder Dev-Stub über {@code archiv.ki.embedding-stub} (Spec 01, Aufgabe 1.11).
 */
public interface EmbeddingPort {

    int BATCH = 16;
    int DIMENSION = 3584;

    /** @param texte 1 bis {@link #BATCH} Texte */
    Antwort einbetten(List<String> texte);

    /** Ergebnis eines Aufrufs. */
    sealed interface Antwort permits Vektoren, OhneVektoren, Ausfall, OhneZugang {
    }

    /** Je Text ein Vektor mit {@link #DIMENSION} Werten, in der Reihenfolge der Texte. */
    record Vektoren(List<float[]> vektoren, KiPort.Verbrauch verbrauch) implements Antwort {
        public Vektoren {
            vektoren = List.copyOf(vektoren);
        }
    }

    /** Dev-Stub (Profil {@code dev}, {@code lokal}): kein Request, keine Vektoren. */
    record OhneVektoren() implements Antwort {
    }

    /**
     * Das Modell hat nicht oder nicht passend geantwortet (Zeitüberschreitung, HTTP-Fehler, falsche Anzahl oder
     * Dimension): Fehlversuch des Auftrags (Spec 01, Req 5.4). {@code grund} nennt nur Klassennamen (Req 16.7).
     */
    record Ausfall(String grund, KiPort.Verbrauch verbrauch) implements Antwort {
    }

    /** Kein Request, weil das Token fehlt (Secret-Datei {@code infomaniak_ai_token}); eine Wiederholung hilft nicht. */
    record OhneZugang() implements Antwort {
    }
}
