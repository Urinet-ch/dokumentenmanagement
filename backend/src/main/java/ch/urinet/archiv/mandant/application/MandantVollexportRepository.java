package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Driven Port: Status und Prüfsumme eines Vollexports im Schema des Mandanten. */
public interface MandantVollexportRepository {

    enum Status {
        OFFEN, FERTIG, FEHLER
    }

    record Vollexport(UUID id, Status status, Optional<String> schluessel, Optional<String> versionId,
                      Optional<String> sha256, Optional<Long> groesse, Instant erstellt, Optional<Instant> fertig) {
    }

    boolean anlegen(UUID id);

    Optional<Vollexport> laden(UUID id);

    boolean fertig(UUID id, AbgelegteDatei zip, int anzahlDokumente);

    boolean fehler(UUID id, UUID fehlerId);
}
