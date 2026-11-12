package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.dokument.domain.DokumentStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driven Port für den monatlichen Prüflauf (Aufgabe 5.3; Req 15.5, 15.9, 15.10): liest die Dateien unter
 * {@code archiv/} mit gespeicherter Versions-ID und SHA-256 im Schema des Mandanten aus dem Kontext. Nur lesend, in
 * der Transaktion des Aufrufers.
 */
public interface DokumentPrueflaufPort {

    /**
     * Dokumente mit mindestens einer Datei unter {@code archiv/} (Original, Arbeitsfassung oder PDF/A), nach ID
     * aufsteigend, nach {@code nach}, höchstens {@code anzahl}; alle Status.
     */
    List<Pruefdokument> archivierte(Optional<UUID> nach, int anzahl);

    /** Welche der Versionen unter diesen Schlüsseln in der Datenbank stehen (Dateien und PDF/A, alle Status). */
    Set<Bekannt> bekannte(Collection<String> schluessel);

    /** Status der genannten Dokumente; fehlende IDs fehlen in der Antwort. */
    Map<UUID, DokumentStatus> status(Collection<UUID> ids);

    record Pruefdokument(UUID id, DokumentStatus status, Sperrvorgabe vorgabe, List<Datei> dateien) {

        public Pruefdokument {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(vorgabe, "vorgabe");
            dateien = List.copyOf(dateien);
        }
    }

    /** Eine Datei unter {@code archiv/}; {@code versionId} und {@code sha256} können fehlen (dann Abweichung). */
    record Datei(String schluessel, Optional<String> versionId, Optional<String> sha256) {

        public Datei {
            Objects.requireNonNull(schluessel, "schluessel");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    record Bekannt(String schluessel, String versionId) {
    }
}
