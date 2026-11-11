package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Datei;
import ch.urinet.archiv.dokument.domain.DokumentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port für die Archivierung durch den Worker (Aufgabe 3.4, Req 6.3, 6.4, 6.8; design.md «Ablage»), im Schema
 * des Mandanten aus dem Kontext, ohne Sichtregel. Jede Abfrage hat eine eigene Zeitgrenze unter der Zeitgrenze des
 * Auftrags (design.md «Pflichten der Handler», (1)). Die Status-Wechsel greifen nur aus {@code ARCHIVIERUNG}.
 */
public interface DokumentArchivierungPort {

    Optional<Archivierungsstand> laden(UUID id);

    /**
     * Hält das Ergebnis fest (Req 6.3): je Datei Schlüssel und Versions-ID unter {@code archiv/}, {@code sperre_bis},
     * {@code archiviert} und Status {@code ARCHIVIERT}; nur aus {@code ARCHIVIERUNG}.
     *
     * @return {@code false}, wenn das Dokument nicht mehr in {@code ARCHIVIERUNG} ist (nichts geschrieben)
     */
    boolean festhalten(UUID id, List<Archiviert> dateien, LocalDate sperreBis);

    /** {@code ARCHIVIERUNG} → {@code ARCHIVIERUNG_FEHLER} mit Fehlertext; {@code false}, wenn nichts geändert wurde. */
    boolean fehlerSetzen(UUID id, String fehlertext);

    /**
     * Stand eines Dokuments für die Archivierung.
     *
     * @param gesetzlichePflicht der bestätigten Art (Req 6.12)
     * @param aufbewahrenBis     {@code null} = unbefristet (Req 6.9)
     * @param dateien            aktive Originale und Arbeitsfassungen
     * @param pdfaFehlt          ohne PDF/A bestätigt (Req 5.9)
     * @param pdfa               PDF/A, solange abgelegt; leer bei {@code pdfa_fehlt}
     */
    record Archivierungsstand(UUID id, DokumentStatus status, Instant eingang, boolean gesetzlichePflicht,
                              LocalDate aufbewahrenBis, boolean fristAbgelaufen, List<Datei> dateien,
                              boolean pdfaFehlt, Optional<Datei> pdfa) {
        public Archivierungsstand {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(eingang, "eingang");
            dateien = List.copyOf(dateien);
            Objects.requireNonNull(pdfa, "pdfa");
        }
    }

    /** Eine archivierte Datei; {@code rolle} {@code PDFA} betrifft die Spalten am Dokument. */
    record Archiviert(DateiRolle rolle, int nr, String s3Schluessel, String s3Version) {
        public Archiviert {
            Objects.requireNonNull(rolle, "rolle");
            Objects.requireNonNull(s3Schluessel, "s3Schluessel");
            Objects.requireNonNull(s3Version, "s3Version");
        }
    }
}
