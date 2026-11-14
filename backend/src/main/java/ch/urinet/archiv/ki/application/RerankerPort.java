package ch.urinet.archiv.ki.application;

import java.util.List;

/**
 * Driven Port zum Reranker (Spec 02, Req 2.2; design.md «Embeddings und Hybridsuche», Schritt 4; Spike 0.3, Abschnitt 6):
 * ordnet die Texte zur Frage neu, genau ein Request je Aufruf ohne Wiederholung, Zeitgrenze
 * {@code archiv.suche.reranker-zeitgrenze}. Modell {@code archiv.ki.reranker-modell}, die Skala der Werte hängt vom
 * Modell ab. Adapter oder Dev-Stub über {@code archiv.ki.reranker-stub} (Spec 01, Aufgabe 1.11). Gründe nennen nur
 * Klassennamen, nie Frage oder Texte (Req 16.7).
 */
public interface RerankerPort {

    /**
     * @param frage Suchbegriff
     * @param texte Texte der Kandidaten, mindestens einer
     * @param topN  verlangte Zahl der Antwort, 1 bis Zahl der Texte
     */
    Antwort sortieren(String frage, List<String> texte, int topN);

    /** Ergebnis eines Aufrufs. */
    sealed interface Antwort permits Sortiert, OhneSortierung, Ausfall, OhneZugang {
    }

    /**
     * Gültige Antwort: genau {@code topN} verschiedene Indizes in die Texte und ihre Werte, beide in der Reihenfolge des
     * Modells (beste zuerst).
     */
    record Sortiert(List<Integer> indizes, List<Double> werte, KiPort.Verbrauch verbrauch) implements Antwort {
        public Sortiert {
            indizes = List.copyOf(indizes);
            werte = List.copyOf(werte);
            if (indizes.size() != werte.size()) {
                throw new IllegalArgumentException("je Index ein Wert");
            }
        }
    }

    /** Dev-Stub (Profil {@code dev}, {@code lokal}): kein Request, keine Reihenfolge. */
    record OhneSortierung() implements Antwort {
    }

    /**
     * Keine oder keine gültige Antwort (Zeitüberschreitung, HTTP-Fehler, Index ausserhalb, doppelt, falsche Zahl);
     * {@code verbrauch} mit den Tokens, die eine Antwort meldete, sonst 0.
     */
    record Ausfall(String grund, KiPort.Verbrauch verbrauch) implements Antwort {
    }

    /** Kein Request, weil das Token fehlt (Secret-Datei {@code infomaniak_ai_token}). */
    record OhneZugang() implements Antwort {
    }
}
