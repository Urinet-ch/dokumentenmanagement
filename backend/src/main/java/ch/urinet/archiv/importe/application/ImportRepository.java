package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Läufe, Zeilen und Zuordnungen des CSV-Imports im Schema des Mandanten der Anfrage ({@code import_lauf},
 * {@code import_zeile}, {@code import_zuordnung}). Läufe anderer Mandanten sind nicht sichtbar.
 */
public interface ImportRepository {

    void laufAnlegen(ImportLauf lauf, List<ImportZeile> zeilen);

    Optional<ImportLauf> lauf(UUID id);

    /** Wie {@link #lauf(UUID)}, sperrt den Lauf aber bis zum Ende der Transaktion (gleichzeitige Zuordnungen). */
    Optional<ImportLauf> laufSperren(UUID id);

    List<ImportZeile> zeilen(UUID laufId);

    /** Schreibt Ergebnis und Meldung der Zeilen und Status und Zähler des Laufs. */
    void pruefungAktualisieren(ImportLauf lauf, List<ImportZeile> zeilen);

    Optional<GespeicherteZuordnung> zuordnung(ImportArt art);

    void zuordnungSpeichern(GespeicherteZuordnung zuordnung);
}
