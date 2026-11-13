package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: Dokumente im Schema des Mandanten der laufenden Anfrage (ADR-002). Jede Abfrage für einen Benutzer
 * filtert nach der {@link Sichtregel}, damit Treffer, Zähler und Paging unsichtbare Dokumente nie verraten (Req 2.3).
 */
public interface DokumentRepository {

    Optional<Dokument> finden(UUID id, Sichtregel regel);

    boolean sichtbar(UUID id, Sichtregel regel);

    /**
     * Gesicherte Fassungen eines Dokuments (Req 10.1), neueste zuerst, ohne Sichtregel: Der Aufrufer hat das Dokument
     * vorher mit {@link #finden} für den Benutzer gelesen.
     */
    List<Fassung> fassungen(UUID id);

    /**
     * Pfad und Versions-ID einer Datei des sichtbaren, nicht verworfenen Dokuments (Req 14.6): {@code PDFA} aus dem
     * Dokument ({@code nr} 0), sonst die aktive Zeile in {@code dokument_datei}. Leer, wenn das Dokument unsichtbar
     * oder verworfen ist oder die Datei (noch) keine Versions-ID hat.
     */
    Optional<DateiFundstelle> dateiFinden(UUID id, Sichtregel regel, DateiRolle rolle, int nr);

    /** Eingangskorb nach A5, neueste zuerst; {@code seite} beginnt bei 0. */
    Seite<Dokument> eingangskorb(Sichtregel regel, int seite, int groesse);

    /** Alle Dokumente der Art, verworfene eingeschlossen, ohne Sichtregel (Req 9.4). */
    long anzahlMitArt(String art);

    /**
     * Sperrt die Prüfsumme bis zum Ende der Transaktion (gleichzeitige Uploads derselben Datei warten aufeinander) und
     * liefert das nicht verworfene Dokument, zu dem sie schon als Original gehört (Req 3.4), ohne Sichtregel.
     */
    Optional<UUID> dubletteSperren(String sha256);

    /** Legt ein Dokument im Status {@code NEU} an, Vertraulichkeit {@code PERSONAL} bis zur Bestätigung (Req 2.21). */
    void anlegen(NeuesDokument dokument);

    /** Schreibt die Zeile in {@code dokument_datei} sofort (vor der Ablage, Regel 3); die Versions-ID folgt danach. */
    void dateiAnlegen(NeueDatei datei);

    void dateiAbgelegt(UUID dokumentId, DateiRolle rolle, int nr, String s3Version);
}
