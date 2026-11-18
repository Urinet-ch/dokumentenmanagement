package ch.urinet.archiv.wissen.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Driven Port für die Vorprüfung und Warteschlange eines Ingests im aktuellen Mandantenschema. */
public interface WissenIngestPort {

    Optional<Quelle> quelleLaden(UUID dokumentId);

    boolean entwurfFuerDokumentOffen(UUID dokumentId);

    boolean hatAktivenIngest();

    UUID entwurfAnlegen(NeuerEntwurf entwurf);

    void dokumentHinweisSetzen(UUID dokumentId, String hinweis);

    long verbrauchImZeitraum(Instant von, Instant bis);

    record Quelle(String art, String status, String vertraulichkeit, String volltext, Integer seiten,
                  boolean pdfaVorhanden, Integer budgetTokens, Integer monatsbudgetTokens) {
    }

    record NeuerEntwurf(UUID id, UUID dokumentId, Integer seiteVon, Integer seiteBis, String status,
                        String schritt, String modell, String erstelltVon, String hinweis) {
    }
}
