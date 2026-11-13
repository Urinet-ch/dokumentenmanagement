package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Chunk;
import ch.urinet.archiv.ki.domain.Chunker;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port für {@code m_<code>.chunk} des Mandanten aus dem Kontext (Spec 02, Req 2.1, 2.4): liest die Angaben des
 * Dokuments für den {@link Chunker} und ersetzt die Abschnitte eines Dokuments. Schreibt in der laufenden Transaktion.
 */
public interface ChunkPort {

    /** Angaben des Dokuments; leer, wenn es im Mandanten fehlt. */
    Optional<Stand> laden(UUID dokumentId);

    /**
     * Löscht alle Abschnitte des Dokuments und legt die neuen an. Ohne Vektoren ({@code null}: Stub oder kein Zugang)
     * bleiben vorhandene Abschnitte mit Vektor unverändert, damit ein fehlender Zugang keine Embeddings löscht.
     *
     * Fügt in kleinen Teilen mit je eigener Zeitgrenze ein und prüft zwischen den Teilen auf eine Unterbrechung.
     *
     * @return {@code false}, wenn die vorhandenen Abschnitte geblieben sind
     * @throws InterruptedException nach einer Unterbrechung (die Transaktion rollt zurück)
     */
    boolean ersetzen(UUID dokumentId, List<Eingebettet> chunks) throws InterruptedException;

    /**
     * Archivierte Dokumente mit einer ID über {@code nach} (aufsteigend), höchstens {@code anzahl}; mit
     * {@code nurFehlende} nur solche ohne Abschnitt oder mit einem Abschnitt ohne Vektor.
     */
    List<UUID> zurNachberechnung(Optional<UUID> nach, int anzahl, boolean nurFehlende);

    /** @param archiviert Status {@code ARCHIVIERT} */
    record Stand(UUID id, boolean archiviert, Chunker.Quelle quelle) {
    }

    /** Abschnitt mit seinem Vektor ({@code null} ohne Vektor). */
    record Eingebettet(Chunk chunk, float[] vektor) {
    }
}
