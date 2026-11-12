package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Versionsseite;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: Stand und Abweichungen des monatlichen Prüflaufs im Schema des Mandanten ({@code prueflauf},
 * {@code prueflauf_abweichung}), in der Transaktion des Aufrufers. Nur IDs, Schlüssel und Zähler (Req 16.7).
 */
public interface PrueflaufRegister {

    /** Neuer Lauf; leer, wenn es für {@code monat} (JJJJ-MM) schon einen gibt. */
    Optional<UUID> anlegen(Optional<String> monat, Stufe stufe);

    Optional<Lauf> laden(UUID id);

    /**
     * Schreibt das Ergebnis des Teilauftrags {@code teil} fort: nur wenn der Lauf auf {@code teil - 1} steht
     * (idempotent); sonst {@code false} und nichts geschrieben.
     */
    boolean fortschreiben(UUID id, int teil, Fortsetzung weiter, Zaehler zuwachs, List<Abweichung> abweichungen);

    /** Die ersten {@code anzahl} Abweichungen des Laufs in der Reihenfolge der Feststellung. */
    List<Abweichung> abweichungen(UUID id, int anzahl);

    enum Phase { DATEIEN, ARCHIV_VERSIONEN, MANIFEST_VERSIONEN, ABGESCHLOSSEN }

    enum Art {
        /** SHA-256 der gelesenen Version weicht von der Datenbank ab (Req 15.5). */
        PRUEFSUMME,
        /** Die gespeicherte Version fehlt oder ist eine Löschmarke (Req 15.5). */
        VERSION_FEHLT,
        /** Version unter {@code archiv/} ohne Eintrag in der Datenbank (Req 15.10). */
        FREMDE_VERSION,
        /** Löschmarke unter {@code archiv/} oder {@code manifest/} (Req 15.10). */
        LOESCHMARKE,
        /** Nur Stufe B: verlangte Sperre fehlt oder ist abgelaufen (Req 6.9, 15.4). */
        SPERRE_FEHLT,
        /** Datei oder Manifest-Version nicht prüfbar oder Sperre nicht verlängert (S3- oder Lesefehler); weitergeprüft. */
        PRUEFFEHLER
    }

    record Lauf(UUID id, Stufe stufe, int teil, Fortsetzung stand, Zaehler zaehler) {

        public Lauf {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(stufe, "stufe");
            Objects.requireNonNull(stand, "stand");
            Objects.requireNonNull(zaehler, "zaehler");
        }
    }

    /** Wo der nächste Teilauftrag weitermacht. */
    record Fortsetzung(Phase phase, Optional<UUID> nachDokument, Optional<Versionsseite.Marke> marke) {

        public Fortsetzung {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(nachDokument, "nachDokument");
            Objects.requireNonNull(marke, "marke");
        }

        public static Fortsetzung beginn(Phase phase) {
            return new Fortsetzung(phase, Optional.empty(), Optional.empty());
        }
    }

    record Zaehler(int dateien, int versionen, int manifeste, int verlaengert, int nurGezaehlt, int abweichungen) {

        public static final Zaehler NULL = new Zaehler(0, 0, 0, 0, 0, 0);

        public Zaehler plus(Zaehler z) {
            return new Zaehler(dateien + z.dateien, versionen + z.versionen, manifeste + z.manifeste,
                    verlaengert + z.verlaengert, nurGezaehlt + z.nurGezaehlt, abweichungen + z.abweichungen);
        }
    }

    record Abweichung(Art art, Optional<UUID> dokumentId, String schluessel, Optional<String> versionId) {

        public Abweichung {
            Objects.requireNonNull(art, "art");
            Objects.requireNonNull(dokumentId, "dokumentId");
            Objects.requireNonNull(schluessel, "schluessel");
            Objects.requireNonNull(versionId, "versionId");
        }
    }
}
