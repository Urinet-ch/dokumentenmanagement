package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.domain.DokumentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port für die Verarbeitung eines Dokuments durch den Worker (Aufgabe 2.6, Req 5.1–5.4, 5.9, 5.10): Stand lesen,
 * Status setzen und Ergebnisse speichern, im Schema des Mandanten aus dem Kontext. Jede Abfrage hat eine eigene
 * Zeitgrenze unter der Zeitgrenze des Auftrags (design.md «Pflichten der Handler», (1)). Die Status-Wechsel sind
 * bedingt: Sie greifen nur bei {@code NEU} oder {@code VERARBEITUNG}, damit ein verspäteter Lauf ein schon bestätigtes,
 * verworfenes oder archiviertes Dokument nie zurücksetzt.
 */
public interface DokumentVerarbeitungPort {

    /** Stand des Dokuments ohne Sichtregel (der Worker handelt für den Mandanten); leer, wenn es fehlt. */
    Optional<Verarbeitungsstand> laden(UUID id);

    /**
     * {@code NEU}/{@code VERARBEITUNG} → {@code VERARBEITUNG}, solange noch kein Ergebnis gespeichert ist; {@code false},
     * wenn die Bedingung nicht hält (nichts geändert).
     */
    boolean verarbeitungBeginnen(UUID id);

    /**
     * Schreibt PDF/A (oder den Vermerk {@code pdfa_fehlt} mit Fehlertext), Seitenzahl und Volltext und setzt den
     * Status; nur bei {@code VERARBEITUNG} und solange noch kein Ergebnis gespeichert ist.
     *
     * @return {@code false}, wenn die Bedingung nicht hält (nichts geschrieben)
     */
    boolean ergebnisSpeichern(UUID id, Verarbeitungsergebnis ergebnis, DokumentStatus neuerStatus);

    /** {@code NEU}/{@code VERARBEITUNG} → {@code FEHLER} mit Fehlertext; {@code false}, wenn nichts geändert wurde. */
    boolean fehlerSetzen(UUID id, String fehlertext);

    /** Stand für den KI-Vorschlag (Aufgabe 3.2, Req 7.1); leer, wenn das Dokument fehlt. */
    Optional<KiStand> kiStandLaden(UUID id);

    /**
     * Speichert den KI-Vorschlag ({@code ki_vorschlag}, {@code ki_sicherheit} als JSON-Objekte), verknüpft bei
     * {@code partnerId} den Kontakt (nur, wenn noch keiner verknüpft ist), hält ihn in {@code ki_partner_id} als Stand
     * des Abgleichs fest (Req 7.7) und setzt {@code EINGANGSKORB} (Req 7.1, 7.4). Nur bei {@code VERARBEITUNG} oder
     * {@code EINGANGSKORB}, solange nicht bestätigt, ohne Vorschlag und mit {@code version = erwarteteVersion}; ändert
     * sonst nichts, setzt also einen Status nie zurück und überschreibt keine Änderung des Benutzers.
     *
     * @return {@code false}, wenn die Bedingung nicht hält (nichts geschrieben)
     */
    boolean vorschlagSpeichern(UUID id, int erwarteteVersion, Map<String, Object> vorschlag,
                               Map<String, BigDecimal> sicherheit, UUID partnerId, String partnerName);

    /**
     * Req 7.1, 7.3: ohne Vorschlag {@code VERARBEITUNG} → {@code EINGANGSKORB}; {@code false}, wenn das Dokument nicht
     * mehr in {@code VERARBEITUNG} ist (nichts geändert).
     */
    boolean ohneVorschlagWeiter(UUID id);

    /**
     * Stand eines Dokuments für den KI-Vorschlag.
     *
     * @param pdfa PDF/A der Ablage; leer bei {@code pdfa_fehlt}
     * @param mime Medientyp des Originals (Nr. 1), für den Bildweg (Req 7.1)
     */
    record KiStand(UUID id, DokumentStatus status, boolean bestaetigt, int version, boolean pdfaFehlt,
                   boolean vorschlagVorhanden, String volltext, Instant eingang, Optional<Datei> pdfa, String mime) {
        public KiStand {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(eingang, "eingang");
            Objects.requireNonNull(pdfa, "pdfa");
        }
    }

    /**
     * Stand eines Dokuments für die Verarbeitung.
     *
     * @param ergebnisVorhanden PDF/A gespeichert oder {@code pdfa_fehlt} gesetzt (ein früherer Lauf war erfolgreich)
     * @param dateien           aktive, abgelegte Dateien (Original, Arbeitsfassung) nach Rolle und Nummer
     */
    record Verarbeitungsstand(UUID id, DokumentStatus status, Instant eingang, String quelle,
                              boolean ergebnisVorhanden, List<Datei> dateien) {
        public Verarbeitungsstand {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(eingang, "eingang");
            dateien = List.copyOf(dateien);
        }
    }

    /** Eine Datei aus {@code dokument_datei}; {@code s3Version} fehlt, solange die Ablage nicht bestätigt ist. */
    record Datei(DateiRolle rolle, int nr, String mime, String s3Schluessel, String s3Version, String sha256) {
    }

    /**
     * Ergebnis eines Laufs. Entweder PDF/A ({@code sha256Pdfa}, {@code s3Pdfa}, {@code s3VersionPdfa}) oder
     * {@code pdfaFehlt} mit {@code fehlertext} (Req 5.9).
     */
    record Verarbeitungsergebnis(String sha256Pdfa, String s3Pdfa, String s3VersionPdfa, boolean pdfaFehlt,
                                 String fehlertext, Integer seiten, String volltext) {
        public Verarbeitungsergebnis {
            Objects.requireNonNull(volltext, "volltext");
            boolean pdfa = sha256Pdfa != null && s3Pdfa != null && s3VersionPdfa != null;
            if (pdfa == pdfaFehlt || (pdfaFehlt && (fehlertext == null || fehlertext.isBlank()))) {
                throw new IllegalArgumentException("Entweder PDF/A oder pdfa_fehlt mit Fehlertext");
            }
            if (seiten != null && seiten < 1) {
                throw new IllegalArgumentException("Seitenzahl ohne Seiten");
            }
        }
    }
}
