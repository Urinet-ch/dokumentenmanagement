package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WissenExportRepository {
    enum Status { OFFEN, FERTIG, FEHLER }

    record Export(UUID id, Status status, String benutzer, String rolle, Instant erstellt, Instant fertig,
                  String schluessel, String version, String sha256, Long groesse, UUID fehlerId) {
        public boolean herunterladbar(Instant jetzt) {
            return status == Status.FERTIG && fertig != null && jetzt.isBefore(fertig.plus(Duration.ofHours(24)));
        }
    }

    void anlegen(UUID id, String benutzer, String rolle);

    Optional<Export> laden(UUID id);

    boolean fertig(UUID id, AbgelegteDatei zip);

    boolean fehler(UUID id, UUID fehlerId);
}
