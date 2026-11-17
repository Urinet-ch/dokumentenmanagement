package ch.urinet.archiv.integration.application.webseite;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Mandantenspezifische Referenzen der Webseiten-Erfassung (Spec 03, Req 10.3, 10.4). */
public interface WebseitenErfassungPort {

    void sperren(String webseite, String referenz);

    Optional<Referenz> referenzFinden(String webseite, String referenz);

    void referenzSetzen(String webseite, String referenz, UUID dokumentId, String sha256);

    void referenzLoeschen(String webseite, String referenz);

    Map<UUID, String> referenzen(String webseite, List<UUID> dokumente);

    record Referenz(UUID dokumentId, String sha256, String status) {
    }
}
