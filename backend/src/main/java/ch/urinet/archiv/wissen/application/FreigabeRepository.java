package ch.urinet.archiv.wissen.application;

import java.util.Optional;
import java.util.UUID;

public interface FreigabeRepository {
    Optional<Aenderung> sperren(UUID entwurfId, int nummer);

    void verwerfen(long aenderungId, int etag, String benutzer, String grund);

    boolean entwurfAbschliessen(UUID entwurfId);

    void abschlussProtokollieren(UUID entwurfId);

    record Aenderung(long id, UUID seiteId, String entwurfStatus, String anlass, String status, int etag) {
    }
}
