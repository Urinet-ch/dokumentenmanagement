package ch.urinet.archiv.wissen.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WissenSichtbarkeitRepository {

    List<WissensHuelleZugriff> aktuelleSeiten();

    Optional<WissensHuelleZugriff> aktuelleSeite(UUID seiteId);

    Optional<WissensHuelleZugriff> version(UUID seiteId, int nummer);
}
