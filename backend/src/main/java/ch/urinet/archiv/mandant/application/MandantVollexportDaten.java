package ch.urinet.archiv.mandant.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Gelesene Mandantendaten für den Vollexport; alle Abfragen laufen im Mandanten-Kontext. */
public interface MandantVollexportDaten {

    record Datei(String schluessel, String versionId, String sha256, String zipPfad) {
    }

    record Dokument(UUID id, String status, String art, String metadatenJson, List<Datei> dateien) {
        public Dokument {
            dateien = List.copyOf(dateien);
        }
    }

    record Manifestdatei(LocalDate datum, String endung, String schluessel, String versionId, String sha256) {
    }

    /** Höchstens {@code anzahl} Dokumente nach ID geordnet, nach {@code nach}. */
    List<Dokument> seite(Optional<UUID> nach, int anzahl);

    /** Alle Tages-Manifeste samt Signaturen, Zeitstempeln und Prüfdaten des Mandanten. */
    List<Manifestdatei> manifeste();
}
