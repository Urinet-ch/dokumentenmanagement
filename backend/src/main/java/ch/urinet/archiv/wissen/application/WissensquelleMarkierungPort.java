package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WissensquelleMarkierungPort {

    boolean dokumentSperren(UUID dokumentId);

    boolean aktiv(UUID dokumentId);

    void markieren(UUID dokumentId, String benutzer);

    boolean entfernen(UUID dokumentId, String benutzer);

    List<Quelle> quellen(Sichtregel sicht);

    record Quelle(UUID dokumentId, String titel, String art, Instant markiertAm, String ingestStatus) {
    }
}
