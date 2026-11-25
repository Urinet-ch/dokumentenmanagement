package ch.urinet.archiv.wissen.application;

import java.util.Optional;
import java.util.UUID;

public interface WebseitenfreigabePort {
    record Stand(int version, long quellenRevision, boolean freigegeben, boolean freigabefaehig,
                 String hoechsteZugriffsstufe) {
    }

    Optional<Stand> lesen(UUID seiteId, boolean sperren);

    void setzen(UUID seiteId, Stand stand, boolean freigeben, String benutzer);
}
