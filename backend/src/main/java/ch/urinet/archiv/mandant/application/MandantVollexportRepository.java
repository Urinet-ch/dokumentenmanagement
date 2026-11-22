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

    enum Fehlerart {
        KONFIGURATION_ZU_GROSS
    }

    record Vollexport(UUID id, Status status, Optional<String> schluessel, Optional<String> versionId,
                      Optional<String> sha256, Optional<Long> groesse, Optional<Integer> anzahl, Instant erstellt,
                      Optional<Instant> fertig, Optional<Fehlerart> fehlerart) {
        public Vollexport(UUID id, Status status, Optional<String> schluessel, Optional<String> versionId,
                          Optional<String> sha256, Optional<Long> groesse, Optional<Integer> anzahl, Instant erstellt,
                          Optional<Instant> fertig) {
            this(id, status, schluessel, versionId, sha256, groesse, anzahl, erstellt, fertig, Optional.empty());
        }
    }

    /** Ist-Stand der Dokumente des Mandanten: Anzahl und jüngste Änderung. */
    record Bestand(int dokumente, Optional<Instant> letzteAenderung) {
    }

    Bestand bestand();

    boolean anlegen(UUID id);

    Optional<Vollexport> laden(UUID id);

    boolean fertig(UUID id, AbgelegteDatei zip, int anzahlDokumente);

    boolean fehler(UUID id, UUID fehlerId);

    boolean fehler(UUID id, UUID fehlerId, Fehlerart art);
}
