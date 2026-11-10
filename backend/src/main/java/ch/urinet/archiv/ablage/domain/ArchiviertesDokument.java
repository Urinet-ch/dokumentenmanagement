package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ergebnis von {@code archivieren} (Req 6.3): je Datei Schlüssel unter {@code archiv/}, Versions-ID und SHA-256; die
 * Sperre gilt für alle Dateien gleich ({@code sperre_bis}, leer ohne gesetzliche Pflicht, Req 6.12).
 */
public record ArchiviertesDokument(List<AbgelegteDatei> dateien, Optional<Instant> sperreBis) {

    public ArchiviertesDokument {
        dateien = List.copyOf(dateien);
    }
}
