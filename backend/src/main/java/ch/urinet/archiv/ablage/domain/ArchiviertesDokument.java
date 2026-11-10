package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Ergebnis von {@code archivieren} (Req 6.3): je Datei Schlüssel unter {@code archiv/}, Versions-ID, SHA-256 und die
 * tatsächliche Sperre im Bucket. {@code sperreBis} ist die späteste Sperre der Dateien ({@code sperre_bis}); leer, wenn
 * keine Datei gesperrt ist (ohne gesetzliche Pflicht, Req 6.12). Ein neuer Versuch übernimmt eine schon gesperrte Kopie
 * mit ihrer Sperre, auch wenn die Vorgabe inzwischen keine verlangt (Req 15.10).
 */
public record ArchiviertesDokument(List<AbgelegteDatei> dateien, Optional<Instant> sperreBis) {

    public ArchiviertesDokument {
        dateien = List.copyOf(dateien);
    }

    public static ArchiviertesDokument aus(List<AbgelegteDatei> dateien) {
        return new ArchiviertesDokument(dateien, dateien.stream().map(AbgelegteDatei::sperreBis)
                .flatMap(Optional::stream).max(Comparator.naturalOrder()));
    }
}
