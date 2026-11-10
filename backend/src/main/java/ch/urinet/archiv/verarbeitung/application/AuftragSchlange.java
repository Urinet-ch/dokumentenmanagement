package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Driven Port: die Auftragsschlange in {@code public.auftrag} (ADR-003). Alle Änderungen an einem geholten Auftrag
 * greifen nur, solange er {@code LAEUFT} und {@code versuche} unverändert ist; sonst liefern sie {@code false}.
 */
public interface AuftragSchlange {

    /** Neuer Auftrag {@code OFFEN}; in einer laufenden Transaktion des Aufrufers Teil davon. */
    long einfuegen(MandantCode mandant, AuftragTyp typ, Nutzlast nutzlast);

    /**
     * Holt höchstens {@code anzahl} fällige {@code OFFEN}-Aufträge der Typen aktiver Mandanten und setzt sie auf
     * {@code LAEUFT} ({@code FOR UPDATE SKIP LOCKED}): Zwei Worker holen nie denselben Auftrag.
     */
    List<Auftrag> holen(Set<AuftragTyp> typen, int anzahl);

    boolean lebenszeichen(Auftrag auftrag);

    boolean abschliessen(Auftrag auftrag);

    /**
     * Zählt einen Fehlversuch: mit Wartezeit zurück auf {@code OFFEN} ({@code nicht_vor = now() + wartezeit}), ohne
     * Wartezeit endgültig {@code FEHLER}. {@code fehler} enthält nie Werte, nur Art und Fehler-ID.
     */
    boolean fehlversuch(Auftrag auftrag, String fehler, Optional<Duration> wartezeit);

    /** {@code LAEUFT}-Aufträge der Typen, deren letztes Lebenszeichen älter ist als ihre Zeitgrenze (Req 5.7). */
    List<Auftrag> verwaiste(Map<AuftragTyp, Duration> zeitgrenzen);

    /** Wie {@link #fehlversuch}, aber nur, wenn das Lebenszeichen weiter seit der Zeitgrenze ausbleibt. */
    boolean verwaistFreigeben(Auftrag auftrag, Duration zeitgrenze, String fehler, Optional<Duration> wartezeit);
}
