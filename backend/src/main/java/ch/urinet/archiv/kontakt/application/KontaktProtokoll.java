package ch.urinet.archiv.kontakt.application;

import java.util.List;
import java.util.UUID;

/**
 * Driven Port: Einträge des Zugriffsprotokolls zu Kontakten (Req 17.8, 17.11, 17.15) in der laufenden Transaktion;
 * scheitert das Schreiben, wirft er und die Aktion rollt zurück (Req 10.8). Nur IDs, Zahlen und der Grund.
 */
public interface KontaktProtokoll {

    /**
     * {@code KONTAKT_ZUSAMMENFUEHREN}: ein Eintrag ohne Dokument mit Quelle, Ziel, Grund und Zahlen und je umgehängtes
     * Dokument einer mit Dokument-ID, Quelle und Ziel, zusammen geschrieben.
     */
    void zusammengefuehrt(UUID quelle, UUID ziel, String grund, KontaktVerweise.Zahlen zahlen, List<UUID> dokumente);

    /** {@code KONTAKT_AKTIV} bzw. {@code KONTAKT_INAKTIV} ohne Dokument mit Kontakt und Grund ({@code null} erlaubt). */
    void markiert(UUID kontakt, boolean aktiv, String grund);
}
