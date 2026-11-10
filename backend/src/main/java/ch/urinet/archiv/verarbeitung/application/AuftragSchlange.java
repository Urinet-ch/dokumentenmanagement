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
 * Eine Zeile, deren Nutzlast nicht lesbar ist, setzen Lesemethoden mit Fehler-ID auf {@code FEHLER} und überspringen
 * sie, damit sie weder Poller noch Aufräumer für alle Mandanten aufhält.
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

    /** Geordneter Stopp: zurück auf {@code OFFEN} und sofort fällig, ohne {@code versuche} zu erhöhen. */
    boolean freigeben(Auftrag auftrag);

    /** {@code LAEUFT}-Aufträge der Typen, deren letztes Lebenszeichen älter ist als ihre Zeitgrenze (Req 5.7). */
    List<Auftrag> verwaiste(Map<AuftragTyp, Duration> zeitgrenzen);

    /** Wie {@link #fehlversuch}, aber nur, wenn das Lebenszeichen weiter seit der Zeitgrenze ausbleibt. */
    boolean verwaistFreigeben(Auftrag auftrag, Duration zeitgrenze, String fehler, Optional<Duration> wartezeit);

    /**
     * {@code FEHLER}-Aufträge aktiver Mandanten ohne {@code nachbearbeitet}, deren Ende länger als die Zeitgrenze
     * ihres Typs zurückliegt (der Worker hat den Hook nicht abgeschlossen, z. B. nach einem Absturz), mit weniger als
     * {@link ch.urinet.archiv.verarbeitung.domain.Nachbearbeitung#MAX_VERSUCHE} gescheiterten Nachbearbeitungen und
     * abgelaufener Wartezeit ({@code nachbearbeitung_nicht_vor}).
     */
    List<Fehlgeschlagen> ohneNachbearbeitung(Map<AuftragTyp, Duration> zeitgrenzen);

    /**
     * Hook nach dem letzten Fehlversuch erledigt: setzt {@code nachbearbeitet}, solange der Auftrag {@code FEHLER} ist
     * (Endzustand, darum ohne Abgleich von {@code versuche}).
     */
    boolean nachbearbeitet(Auftrag auftrag);

    /**
     * Hook gescheitert: {@code nachbearbeitung_versuche = bisher + 1}, mit Wartezeit
     * {@code nachbearbeitung_nicht_vor = now() + wartezeit}; nur, solange der Auftrag {@code FEHLER} ohne
     * {@code nachbearbeitet} ist und {@code bisher} noch stimmt.
     */
    boolean nachbearbeitungGescheitert(Auftrag auftrag, int bisher, Optional<Duration> wartezeit);

    /**
     * Sperrt die Zeile des Auftrags in der laufenden Transaktion des Aufrufers
     * ({@code select … where id = ? and versuche = ? and status = 'LAEUFT' for update}); {@code false}, wenn der
     * Anspruch nicht mehr hält. Bricht ohne laufende Transaktion ab, weil die Sperre sonst sofort wieder frei wäre.
     */
    boolean anspruchSperren(Auftrag auftrag);
}
