package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.DokumentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Statuswechsel aus der Oberfläche (Aufgabe 3.4, design.md «Statusmodell»): Bestätigen, Verwerfen, Wiederholen und die
 * Belegnummern der eigenen Arten. Jede Methode läuft in der Transaktion des Aufrufers im Schema des Mandanten; jede
 * Änderung am Dokument ausser {@link #fristAbgelaufenMarkieren} erhöht {@code version}.
 */
public interface DokumentStatusPort {

    /** Stand ohne Sichtregel; mit {@code sperren} bis zum Ende der Transaktion gesperrt ({@code for update}). */
    Optional<Stand> stand(UUID id, boolean sperren);

    /**
     * Setzt {@code frist_abgelaufen}, solange das Dokument im Eingangskorb steht, ohne {@code version} zu erhöhen: Das
     * Kennzeichen ist keine Änderung der Metadaten, und die erneute Bestätigung gilt für dieselbe Version (Req 9.8).
     */
    void fristAbgelaufenMarkieren(UUID id);

    /** Dokument, das die Belegnummer der Art reserviert hat (Req 8.9). */
    Optional<UUID> belegnummerInhaber(String art, String belegnummer);

    /**
     * Reserviert die Belegnummer der Art für das Dokument und gibt eine andere Reservation desselben Dokuments frei.
     *
     * @return {@code false}, wenn ein anderes Dokument sie inzwischen hält
     */
    boolean belegnummerReservieren(String art, String belegnummer, UUID id);

    /** Gibt jede Reservation des Dokuments frei (Req 11.11). */
    void belegnummerFreigeben(UUID id);

    /**
     * Schreibt die bestätigten Metadaten, setzt {@code ARCHIVIERUNG} und erhöht {@code version}; {@code dokument_finanz}
     * und {@code dokument_vertrag} nur, wenn die Art die Gruppe hat (Zahlstatus und AbaNinja-Felder bleiben unberührt).
     * Sperrt die Zeile mit {@code for update} und prüft Status und Version danach erneut.
     *
     * @return {@code false}, wenn das Dokument nicht mehr im Eingangskorb oder in dieser Version ist (nichts geändert)
     */
    boolean bestaetigen(UUID id, int version, Bestaetigt daten);

    /** {@code VERWORFEN} mit Grund, Dateien als verworfen markiert (Req 6.6). */
    boolean verwerfen(UUID id, int version, String grund);

    /** {@code FEHLER} → {@code NEU}, Fehlertext entfernt (Req 8.7). */
    boolean verarbeitungWiederholen(UUID id, int version);

    /** {@code ARCHIVIERUNG_FEHLER} → {@code ARCHIVIERUNG}, Metadaten unverändert (Req 6.8). */
    boolean archivierungWiederholen(UUID id, int version);

    /**
     * Ein anderes, nicht verworfenes Dokument mit gleicher Art, gleicher Belegnummer und gleichem Partner (Req 8.10),
     * das älteste zuerst; leer ohne Belegnummer oder Partner.
     */
    Optional<UUID> gleicherBeleg(UUID id);

    /**
     * @param zusatzFelder Zusatzfelder mit Wert (nicht {@code null}, nicht leer), für die Pflichtfelder {@code zusatz.*}
     * @param sha256       Prüfsummen aller aktiven Dateien: Originale, Arbeitsfassungen und PDF/A
     */
    record Stand(UUID id, DokumentStatus status, int version, Instant eingang, boolean pdfaFehlt,
                 Set<String> zusatzFelder, List<String> sha256) {
        public Stand {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(eingang, "eingang");
            zusatzFelder = Set.copyOf(zusatzFelder);
            sha256 = List.copyOf(sha256);
        }
    }

    /** Bestätigte Metadaten; {@code finanz} bzw. {@code vertrag} leer, wenn die Art die Gruppe nicht hat. */
    record Bestaetigt(String art, String titel, LocalDate datum, UUID partnerId, String partnerName, String projekt,
                      List<String> tags, String vertraulichkeit, String personBenutzer, String bestaetigtVon,
                      int geschaeftsjahr, LocalDate aufbewahrenBis, boolean fristAbgelaufen,
                      Optional<Finanz> finanz, Optional<Vertrag> vertrag) {
        public Bestaetigt {
            Objects.requireNonNull(art, "art");
            Objects.requireNonNull(vertraulichkeit, "vertraulichkeit");
            Objects.requireNonNull(bestaetigtVon, "bestaetigtVon");
            tags = tags == null ? List.of() : List.copyOf(tags);
            Objects.requireNonNull(finanz, "finanz");
            Objects.requireNonNull(vertrag, "vertrag");
        }
    }

    record Finanz(String belegnummer, String richtung, BigDecimal betrag, String waehrung, LocalDate faelligkeit,
                  String qrReferenz, String konto) {
        public Finanz {
            Objects.requireNonNull(waehrung, "waehrung");
        }
    }

    record Vertrag(LocalDate gueltigAb, LocalDate gueltigBis, String kuendigungsfrist, LocalDate naechsteFrist) {
    }
}
