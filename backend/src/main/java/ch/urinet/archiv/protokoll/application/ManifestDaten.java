package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.Vorher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Driven Port: Daten des Tages-Manifests im Schema des Mandanten aus dem Kontext (Aufgabe 5.2, design.md «Manifest»).
 * Lesen und Schreiben in der Transaktion des Aufrufers, jede Abfrage mit eigener Zeitgrenze.
 */
public interface ManifestDaten {

    /** Das jüngste Manifest, leer vor dem ersten. */
    Optional<ManifestStand> letztes();

    /** Dateien der Dokumente mit {@code archiviert} in {@code [von, bis)}; {@code von = null} heisst ohne untere Grenze. */
    List<ManifestEintrag> eintraege(Instant von, Instant bis);

    /** Fristen der Dokumente aus {@link #eintraege} für die Sperre des Manifests (Req 15.4). */
    Fristen fristen(Instant von, Instant bis);

    /** Blätter der Zeilen von {@code tabelle} mit {@code zeit} in {@code [von, bis)}, nach {@code id}. */
    List<Blatt> blaetter(String tabelle, Instant von, Instant bis);

    /** Hält Manifest, Zeitstempel und Blätter fest (nur Einfügen). */
    void festhalten(NeuesManifest manifest);

    /**
     * Sperrt die Manifest-Erzeugung des Mandanten über JVMs hinweg (Sitzungs-Sperre in PostgreSQL); leer, wenn ein
     * anderer Lauf sie hält. Die Sperre endet mit {@link Sperre#close()} oder mit der Verbindung.
     */
    Optional<Sperre> sperren();

    interface Sperre extends AutoCloseable {
        @Override
        void close();
    }

    /** Stand eines festgehaltenen Manifests; {@code verweis} geht als {@code vorher} in das nächste. */
    record ManifestStand(LocalDate datum, Instant bis, String sha256, Vorher verweis) {
        public ManifestStand {
            Objects.requireNonNull(datum, "datum");
            Objects.requireNonNull(bis, "bis");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(verweis, "verweis");
        }
    }

    /**
     * {@code dokumente}: Anzahl Dokumente; {@code unbefristet}: mindestens eines ohne {@code aufbewahren_bis};
     * {@code spaetestens}: spätestes {@code aufbewahren_bis}.
     */
    record Fristen(long dokumente, boolean unbefristet, Optional<LocalDate> spaetestens) {
    }

    record Blatt(long zeileId, String sha256) {
    }

    record Pruefdatei(String art, String sha256, String s3Version) {
    }

    record Zeitstempel(String dienst, Instant genTime, AbgelegteDatei tsr, List<Pruefdatei> pruefdaten) {
        public Zeitstempel {
            pruefdaten = List.copyOf(pruefdaten);
        }
    }

    record NeuesManifest(ManifestInhalt inhalt, AbgelegteDatei json, AbgelegteDatei sig, List<Zeitstempel> zeitstempel,
                         Map<String, List<Blatt>> blaetter) {
        public NeuesManifest {
            zeitstempel = List.copyOf(zeitstempel);
            blaetter = Map.copyOf(blaetter);
        }
    }
}
