package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissensStufe;

import java.util.Set;
import java.util.UUID;

public record WissensHuelleZugriff(UUID seiteId, WissensStufe mindeststufe, Set<UUID> dokumente) {

    public WissensHuelleZugriff {
        dokumente = Set.copyOf(dokumente);
    }
}
