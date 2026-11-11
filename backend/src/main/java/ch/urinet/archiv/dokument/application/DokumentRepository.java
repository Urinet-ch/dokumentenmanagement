package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: Dokumente im Schema des Mandanten der laufenden Anfrage (ADR-002). Jede Abfrage für einen Benutzer
 * filtert nach der {@link Sichtregel}, damit Treffer, Zähler und Paging unsichtbare Dokumente nie verraten (Req 2.3).
 */
public interface DokumentRepository {

    Optional<Dokument> finden(UUID id, Sichtregel regel);

    boolean sichtbar(UUID id, Sichtregel regel);

    /** Eingangskorb nach A5, neueste zuerst; {@code seite} beginnt bei 0. */
    Seite<Dokument> eingangskorb(Sichtregel regel, int seite, int groesse);

    /** Alle Dokumente der Art, verworfene eingeschlossen, ohne Sichtregel (Req 9.4). */
    long anzahlMitArt(String art);

    /**
     * Ersetzt die Zusatzfelder, wenn die Version noch {@code erwarteteVersion} ist, sonst 412 {@code VERSION_KONFLIKT}.
     * Mit {@code versionSichern} geht die vorherige Fassung mit Benutzer und Grund nach {@code dokument_version}
     * (Req 10.1).
     *
     * @return die neue Version
     */
    int zusatzSpeichern(UUID id, int erwarteteVersion, String zusatzJson, String benutzer, String grund,
                        boolean versionSichern);
}
