package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: Inhalt der Tages-Manifeste für den Vergleich im monatlichen Prüflauf (Req 15.5; Aufgabe 5.3, design.md
 * «Prüflauf», «Umsetzung (Manifest-Vergleich, Req 15.5)»). Die Umsetzung bringt das Manifest (Modul {@code protokoll}).
 * Nur lesend; Signatur, Kette und Zeitstempel prüft das Prüfwerkzeug {@code archiv manifest pruefen} (Req 15.12).
 */
public interface ManifestVergleich {

    /**
     * Das für {@code archiviert} zuständige Manifest aus der Tabelle {@code manifest}: dasjenige, in dessen Fenster
     * {@code [von, bis)} der Zeitpunkt liegt ({@code von} leer heisst ohne untere Grenze). Leer, wenn es keines gibt
     * (archiviert nach {@code bis} des jüngsten Manifests oder noch kein Manifest); dann wird nicht verglichen, weil
     * die Überwachung fehlende Manifeste meldet (Req 15.8).
     */
    Optional<Fenster> zustaendig(MandantCode mandant, Instant archiviert);

    /**
     * Liest die Einträge des Manifests aus der Ablage (älteste Version von {@code manifest/<datum>.json}). Ein Manifest,
     * dessen Einträge sich nicht verlässlich lesen lassen, ist {@link Unbrauchbar}. Ist der Object Storage vorübergehend
     * gestört, kommt {@code AblageGestoert} wie beim Prüfen einer Datei.
     */
    Manifest lesen(MandantCode mandant, Fenster fenster);

    /** Datum, Fenster {@code [von, bis)} und SHA-256 eines festgehaltenen Manifests (Tabelle {@code manifest}). */
    record Fenster(LocalDate datum, Optional<Instant> von, Instant bis, String sha256) {

        public Fenster {
            Objects.requireNonNull(datum, "datum");
            Objects.requireNonNull(von, "von");
            Objects.requireNonNull(bis, "bis");
            Objects.requireNonNull(sha256, "sha256");
        }

        public boolean enthaelt(Instant zeitpunkt) {
            return von.map(v -> !zeitpunkt.isBefore(v)).orElse(true) && zeitpunkt.isBefore(bis);
        }
    }

    /** Gelesenes Manifest eines Datums: {@link Eintraege} oder {@link Unbrauchbar}. */
    sealed interface Manifest permits Eintraege, Unbrauchbar {
        LocalDate datum();
    }

    /** Einträge des Manifests: Dokument, Variante und Nr. → SHA-256. */
    record Eintraege(LocalDate datum, Map<Eintrag, String> sha256) implements Manifest {

        public Eintraege {
            Objects.requireNonNull(datum, "datum");
            sha256 = Map.copyOf(sha256);
        }
    }

    /** Manifest nicht verwendbar; {@code grund} ohne Inhalte, nur für das Log (Req 16.7). */
    record Unbrauchbar(LocalDate datum, String grund) implements Manifest {

        public Unbrauchbar {
            Objects.requireNonNull(datum, "datum");
            Objects.requireNonNull(grund, "grund");
        }
    }

    /** Schlüssel eines Eintrags: Dokument, Variante ({@code original}, {@code arbeitsfassung}, {@code pdfa}), Nr. */
    record Eintrag(UUID dokument, String variante, int nr) {

        public Eintrag {
            Objects.requireNonNull(dokument, "dokument");
            Objects.requireNonNull(variante, "variante");
        }
    }
}
