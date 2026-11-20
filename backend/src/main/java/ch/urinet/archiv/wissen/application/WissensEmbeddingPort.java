package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.domain.Chunker;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WissensEmbeddingPort {
    record Stand(UUID id, int version, Chunker.Quelle quelle) { }
    record SeitenVersion(UUID id, int version) { }

    Optional<Stand> laden(UUID id);

    boolean ersetzen(UUID id, int version, List<ChunkPort.Eingebettet> chunks);

    List<SeitenVersion> nachberechnen(Optional<UUID> nach, int groesse);
}
