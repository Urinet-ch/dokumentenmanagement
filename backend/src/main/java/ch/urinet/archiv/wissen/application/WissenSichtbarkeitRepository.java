package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.List;
import java.util.UUID;

public interface WissenSichtbarkeitRepository {

    List<UUID> sichtbareSeiten(Sichtregel regel);

    boolean aktuelleSeiteSichtbar(UUID seiteId, Sichtregel regel);

    boolean versionSichtbar(UUID seiteId, int nummer, Sichtregel regel);
}
