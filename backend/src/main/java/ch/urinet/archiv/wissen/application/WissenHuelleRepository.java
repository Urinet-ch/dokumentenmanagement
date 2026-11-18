package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensStufe;

import java.util.Set;
import java.util.UUID;

public interface WissenHuelleRepository {

    WissensGraph graphLaden();

    Set<UUID> seitenMitDokument(UUID dokumentId);

    void aktuelleHuelleSetzen(UUID seiteId, Set<UUID> dokumente);

    void vertraulichkeitSetzen(UUID seiteId, WissensStufe stufe);

    void versionsHuelleEinfuegen(UUID seiteId, int version, Set<UUID> dokumente);
}
