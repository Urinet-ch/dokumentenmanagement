package ch.urinet.archiv.wissen.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Driven Port für die Vorprüfung und Warteschlange eines Ingests im aktuellen Mandantenschema. */
public interface WissenIngestPort {

    Optional<Quelle> quelleLaden(UUID dokumentId);

    /** Ein Ingest-Entwurf des Dokuments läuft oder wartet ({@code IN_ARBEIT}, {@code WARTEND}; Req 5.10). */
    boolean entwurfFuerDokumentLaufend(UUID dokumentId);

    /**
     * Die Warteschlange des Mandanten ist belegt: ein Ingest-Entwurf ist aktiv ({@code IN_ARBEIT}, {@code OFFEN}) oder
     * wartet ({@code WARTEND}). Ein neuer Start reiht sich dann hinten ein und überholt keinen wartenden (Req 5.13).
     */
    boolean warteschlangeBelegt();

    UUID entwurfAnlegen(NeuerEntwurf entwurf);

    /** Summe der Tokens aus {@code ki_verbrauch} mit Zweck {@code WISSEN_*} im Zeitraum [von, bis) (Req 13.6). */
    long verbrauchImZeitraum(Instant von, Instant bis);

    record Quelle(String art, String status, String vertraulichkeit, String volltext, Integer seiten,
                  boolean pdfaVorhanden, Integer budgetTokens, Integer monatsbudgetTokens) {
    }

    record NeuerEntwurf(UUID id, UUID dokumentId, Integer seiteVon, Integer seiteBis, String status,
                        String schritt, String modell, String erstelltVon, String hinweis) {
    }
}
