package ch.urinet.archiv.wissen.application;

import java.util.Set;
import java.util.UUID;

public interface WissenQuellenAenderungPort {
    void quellePruefenSetzen(Set<UUID> seiten);
}
