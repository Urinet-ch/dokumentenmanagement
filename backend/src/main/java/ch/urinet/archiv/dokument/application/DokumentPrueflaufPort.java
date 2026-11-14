package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.dokument.domain.DokumentStatus;

import java.time.Instant;
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

    /**
     * {@code archiviert}: {@code dokument.archiviert}, der Zeitpunkt, nach dem das Tages-Manifest die Dateien aufnimmt
     * (Req 15.1); leer, solange das Dokument nicht archiviert ist (z. B. {@code ARCHIVIERUNG}).
     */
    record Pruefdokument(UUID id, DokumentStatus status, Sperrvorgabe vorgabe, Optional<Instant> archiviert,
                         List<Datei> dateien) {

        public Pruefdokument {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(vorgabe, "vorgabe");
            Objects.requireNonNull(archiviert, "archiviert");
            dateien = List.copyOf(dateien);
        }
    }

    /**
     * Eine Datei unter {@code archiv/}; {@code versionId} und {@code sha256} können fehlen (dann Abweichung).
     * {@code variante} ({@code original}, {@code arbeitsfassung}, {@code pdfa}) und {@code nr} sind der Schlüssel des
     * Eintrags im Tages-Manifest; {@code imManifest} sagt, ob das Manifest die Datei nach derselben Regel wie
     * {@code ManifestDaten.eintraege} enthält ({@code dokument_datei} nur mit {@code status = 'AKTIV'}, PDF/A nur mit
     * {@code sha256_pdfa}).
     */
    record Datei(String schluessel, Optional<String> versionId, Optional<String> sha256, String variante, int nr,
                 boolean imManifest) {

        public Datei {
            Objects.requireNonNull(schluessel, "schluessel");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(variante, "variante");
        }
    }

    record Bekannt(String schluessel, String versionId) {
    }
}
