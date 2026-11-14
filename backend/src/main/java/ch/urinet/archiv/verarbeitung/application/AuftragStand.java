package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;

import java.time.Instant;
import java.util.Optional;

/** Driven Port: Stand der Aufträge eines Typs je Mandant in {@code public.auftrag} (Req 15.8, 16.5). */
public interface AuftragStand {

    /**
     * Ende ({@code beendet}) des zuletzt in {@code FEHLER} beendeten Auftrags, unabhängig von neueren Aufträgen in
     * {@code OFFEN}, {@code LAEUFT} oder {@code FERTIG}; leer, wenn kein Auftrag gescheitert ist.
     */
    Optional<Instant> letzterFehler(MandantCode mandant, AuftragTyp typ);

    /**
     * Stand des jüngsten Auftrags (höchste id) des Typs für den Mandanten; leer, wenn es keinen gibt. Anders als
     * {@link #letzterFehler} zählt nur dieser eine Auftrag, ein älterer Fehlschlag mit späterem Erfolg bleibt ohne Wirkung.
     *
     * @param status {@code OFFEN}, {@code LAEUFT}, {@code FERTIG} oder {@code FEHLER}
     * @param beendet Ende des Auftrags; leer, solange er offen ist oder läuft
     */
    Optional<Juengster> juengster(MandantCode mandant, AuftragTyp typ);

    record Juengster(String status, Optional<Instant> beendet) {

        public boolean offenOderLaeuft() {
            return "OFFEN".equals(status) || "LAEUFT".equals(status);
        }

        public boolean gescheitert() {
            return "FEHLER".equals(status);
        }
    }
}
