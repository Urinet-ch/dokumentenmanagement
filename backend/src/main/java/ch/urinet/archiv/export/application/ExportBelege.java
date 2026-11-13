package ch.urinet.archiv.export.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: was ein Jahresexport enthält (Spec 02, Req 7.1), aus der Datenbank des Mandanten im Kontext; nur
 * lesend, jede Abfrage mit eigener Zeitgrenze.
 */
public interface ExportBelege {

    /** Eine archivierte Datei: Schlüssel und Versions-ID unter {@code archiv/}, SHA-256 aus der Datenbank, Endung. */
    record Datei(String schluessel, String versionId, String sha256, String endung) {

        public Datei {
            Objects.requireNonNull(schluessel, "schluessel");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(endung, "endung");
        }
    }

    /**
     * Ein Dokument im Status {@code ARCHIVIERT} mit seinen Dateien für den Export: die PDF/A-Fassung, bei
     * {@code pdfa_fehlt} (Spec 01, Req 5.9) die aktiven Originale in ihrer Reihenfolge.
     */
    record Beleg(UUID id, String art, LocalDate datum, Instant eingang, Instant archiviert, String partner,
                 String belegnummer, String richtung, BigDecimal betrag, String waehrung, List<Datei> dateien) {

        public Beleg {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(eingang, "eingang");
            dateien = List.copyOf(dateien);
        }
    }

    /** Eine Datei eines Tages-Manifests unter {@code manifest/} ({@code json}, {@code sig}, {@code <dienst>.tsr}, …). */
    record Manifestdatei(LocalDate datum, String endung, String schluessel, String versionId, String sha256) {
    }

    /** Höchstens {@code anzahl} Belege des Geschäftsjahres nach ID geordnet, nach {@code nach}. */
    List<Beleg> seite(int geschaeftsjahr, Optional<UUID> nach, int anzahl);

    /** Alle Dateien der Manifeste mit Datum von {@code von} bis {@code bis} (beide inklusive), nach Datum. */
    List<Manifestdatei> manifeste(LocalDate von, LocalDate bis);
}
