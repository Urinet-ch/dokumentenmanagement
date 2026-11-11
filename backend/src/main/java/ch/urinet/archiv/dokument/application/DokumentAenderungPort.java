package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Finanz;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Vertrag;
import ch.urinet.archiv.dokument.domain.DokumentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Metadaten eines Dokuments lesen und ändern (Aufgabe 3.5, Req 10.1), in der Transaktion des Aufrufers und im Schema
 * des Mandanten aus dem Kontext.
 */
public interface DokumentAenderungPort {

    /**
     * Metadaten eines Dokuments; {@code aufbewahrenBis} {@code null} heisst unbefristet bzw. noch nicht berechnet,
     * {@code sperreBis} ist der letzte gesperrte Tag. {@code finanz} und {@code vertrag} sind leer, wenn es keine Zeile
     * gibt; {@code zusatzFelder} nennt die Zusatzfelder mit Wert.
     */
    record Metadaten(UUID id, DokumentStatus status, int version, Instant eingang, String art, String titel,
                     LocalDate datum, UUID partnerId, String partnerName, String projekt, List<String> tags,
                     String vertraulichkeit, String personBenutzer, Integer geschaeftsjahr, LocalDate aufbewahrenBis,
                     boolean fristAbgelaufen, LocalDate sperreBis, String zusatz, Set<String> zusatzFelder,
                     Optional<Finanz> finanz, Optional<Vertrag> vertrag) {

        public Metadaten {
            tags = tags == null ? List.of() : List.copyOf(tags);
            zusatzFelder = Set.copyOf(zusatzFelder);
        }
    }

    /** Liest die Metadaten und sperrt die Zeile bis zum Ende der Transaktion ({@code for update}). */
    Optional<Metadaten> ladenUndSperren(UUID id);

    /** Sichert die aktuelle Fassung mit Benutzer, Zeit und Grund in {@code dokument_version} (Req 10.1). */
    void versionSichern(UUID id, String benutzer, String grund);

    /**
     * Schreibt die geänderten Metadaten; {@code status}, {@code version} und {@code eingang} bleiben. Leeres
     * {@code finanz} bzw. {@code vertrag} lässt eine bestehende Zeile unverändert; Zahlstatus und AbaNinja-Felder ändert
     * der Adapter nie (Regel 8).
     *
     * @return die neue Version (für das ETag)
     */
    int speichern(Metadaten daten);

    /** Die archivierten Dateien des Dokuments unter {@code archiv/} mit Versions-ID (für die Sperre, Req 9.7). */
    List<AbgelegteDatei> archivDateien(UUID id);
}
