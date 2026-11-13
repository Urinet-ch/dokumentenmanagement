package ch.urinet.archiv.export.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ein Jahresexport (Zeile in {@code export}, design.md «Jahresexport»). Ein fertiger Export trägt Schlüssel und
 * Versions-ID des ZIP unter {@code exporte/}, dessen SHA-256, Grösse und die Zahl der Belegdateien. {@code benutzer}
 * und {@code rolle} sind die der Anforderung (Protokoll beim Abschluss, Req 7.3).
 */
public record Export(UUID id, int geschaeftsjahr, ExportStatus status, Optional<String> schluessel,
                     Optional<String> versionId, Optional<String> sha256, Optional<Long> groesse,
                     Optional<Integer> anzahl, String benutzer, String rolle, Instant erstellt,
                     Optional<Instant> fertig) {

    public Export {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(erstellt, "erstellt");
    }

    /** Fertig und der Download-Link noch nicht abgelaufen (Req 7.2). */
    public boolean herunterladbar(Instant jetzt) {
        return status == ExportStatus.FERTIG && fertig.isPresent() && !Exportfrist.abgelaufen(fertig.get(), jetzt);
    }

    public Optional<Instant> gueltigBis() {
        return status == ExportStatus.FERTIG ? fertig.map(Exportfrist::gueltigBis) : Optional.empty();
    }
}
