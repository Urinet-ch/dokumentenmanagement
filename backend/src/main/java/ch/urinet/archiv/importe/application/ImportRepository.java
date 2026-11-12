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
 * {@code import_zeile}, {@code import_zuordnung}). Läufe anderer Mandanten sind nicht sichtbar. Zeilen werden in Blöcken
 * geschrieben und gelesen, nie alle auf einmal (Speicher, Entscheid Fabian 11.11.2026).
 */
public interface ImportRepository {

    void laufAnlegen(ImportLauf lauf);

    void zeilenAnlegen(UUID laufId, List<ImportZeile> zeilen);

    /** Schreibt Status und Zähler ({@code zeilen}, {@code fehler}) des Laufs. */
    void laufAktualisieren(ImportLauf lauf);

    Optional<ImportLauf> lauf(UUID id);

    /** Wie {@link #lauf(UUID)}, sperrt den Lauf aber bis zum Ende der Transaktion (gleichzeitige Zuordnungen). */
    Optional<ImportLauf> laufSperren(UUID id);

    /** Grösste Anzahl Werte einer gespeicherten Zeile des Laufs; 0, wenn keine Zeile Werte hat. */
    int breite(UUID laufId);

    /**
     * Höchstens {@code anzahl} Zeilen mit {@code nr > nachNr}, aufsteigend nach {@code nr}; nach der Zeile, mit der die
     * gespeicherten Daten (JSON) zusammen {@code zeichen} Zeichen erreichen, ist Schluss. Mindestens eine Zeile, wenn es
     * noch eine gibt.
     */
    List<ImportZeile> zeilen(UUID laufId, int nachNr, int anzahl, int zeichen);

    /** Schreibt Ergebnis und Meldung der Zeilen. */
    void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen);

    Optional<GespeicherteZuordnung> zuordnung(ImportArt art);

    void zuordnungSpeichern(GespeicherteZuordnung zuordnung);
}
