package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.domain.DublettenEntscheid;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** Schreibt Status, Zähler ({@code zeilen}, {@code fehler}, Vorschau) und Zuordnung des Laufs. */
    void laufAktualisieren(ImportLauf lauf);

    Optional<ImportLauf> lauf(UUID id);

    /** Wie {@link #lauf(UUID)}, sperrt den Lauf aber bis zum Ende der Transaktion (gleichzeitige Zuordnungen). */
    Optional<ImportLauf> laufSperren(UUID id);

    /**
     * Wie {@link #laufSperren(UUID)} für Anfragen über REST, wartet aber höchstens {@code archiv.import.sperre-wartezeit}
     * auf die Sperre. Hält eine andere Transaktion den Lauf länger (z. B. die laufende Übernahme), {@code FachFehler}
     * {@code STATUS_KONFLIKT}, statt die Anfrage minutenlang warten zu lassen.
     */
    Optional<ImportLauf> laufSperrenOderKonflikt(UUID id);

    /**
     * Begrenzt für den Rest der laufenden Transaktion jede Anweisung ({@code archiv.import.anweisung-zeitgrenze}) und
     * jedes Warten auf eine Sperre ({@code archiv.import.sperre-wartezeit}); beide liegen unter der Zeitgrenze der
     * Transaktion, weil diese eine blockierte Anweisung nicht beendet (design.md «Pflichten der Handler» (1)).
     */
    void zeitgrenzenSetzen();

    /** Grösste Anzahl Werte einer gespeicherten Zeile des Laufs; 0, wenn keine Zeile Werte hat. */
    int breite(UUID laufId);

    /**
     * Höchstens {@code anzahl} Zeilen mit {@code nr > nachNr}, aufsteigend nach {@code nr}; nach der Zeile, mit der die
     * gespeicherten Daten (JSON) zusammen {@code zeichen} Zeichen erreichen, ist Schluss. Mindestens eine Zeile, wenn es
     * noch eine gibt.
     */
    List<ImportZeile> zeilen(UUID laufId, int nachNr, int anzahl, int zeichen);

    /**
     * Schreibt Ergebnis, Meldung, Kontakt und Kandidaten der Zeilen; die Fingerabdrücke des Abgleichs fallen weg (neue
     * Prüfung nach einer Zuordnung).
     */
    void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen);

    /** Wie {@link #zeilenAktualisieren}, aber mit den Fingerabdrücken des Abgleichs (Aufgabe 4.2). */
    void abgleichSchreiben(UUID laufId, List<Abgeglichen> zeilen);

    /**
     * Markiert jede Zeile, die einen Fingerabdruck mit einer früheren Zeile desselben Laufs teilt, als
     * {@code DUBLETTE} mit {@code meldung} ({@code {}} wird zur Nummer der frühesten solchen Zeile); Zeilen, die schon
     * {@code DUBLETTE} sind, bleiben. Liefert die Anzahl markierter Zeilen.
     */
    int doppelteMarkieren(UUID laufId, String meldung);

    /** Zeilen des Laufs je Ergebnis. */
    ImportLauf.Zaehler zaehlen(UUID laufId);

    /** Eine Seite der Zeilen (aufsteigend nach {@code nr}), für {@code GET /importe/{id}}. */
    List<ImportZeile> zeilenSeite(UUID laufId, int seite, int groesse);

    /** Zeilen des Laufs mit diesen Nummern (unbekannte Nummern fehlen), aufsteigend nach {@code nr}. */
    List<ImportZeile> zeilenMitNr(UUID laufId, Collection<Integer> nrs);

    /**
     * Ersetzt die Dubletten-Entscheide des Laufs (Req 13.6): Zeilen, die nicht in {@code entscheide} stehen, haben danach
     * keinen Entscheid mehr.
     */
    void entscheideSpeichern(UUID laufId, Map<Integer, DublettenEntscheid> entscheide);

    /** Die Dubletten-Entscheide des Laufs je Zeilennummer. */
    Map<Integer, DublettenEntscheid> entscheide(UUID laufId);

    /** Hält fest, wer die Übernahme ausgelöst hat (Protokoll, Req 13.8); Status und Zeit bleiben. */
    void uebernahmeVormerken(UUID laufId, String benutzer);

    /** Setzt den Lauf auf {@code UEBERNOMMEN} mit Zeit und Zählern der Übernahme (Protokoll, Req 13.8). */
    void uebernahmeAbschliessen(UUID laufId, ImportLauf.Bilanz bilanz);

    /** Eine Seite der Läufe des Mandanten, die neuesten zuerst ({@code GET /importe}). */
    List<ImportLauf> laeufe(int seite, int groesse);

    /** Anzahl der Läufe des Mandanten. */
    long anzahlLaeufe();

    Optional<GespeicherteZuordnung> zuordnung(ImportArt art);

    void zuordnungSpeichern(GespeicherteZuordnung zuordnung);

    /** Eine abgeglichene Zeile mit den Fingerabdrücken ihrer Schlüssel ({@code KontaktAbgleich.fingerabdruecke}). */
    record Abgeglichen(ImportZeile zeile, long[] schluessel) {
    }
}
