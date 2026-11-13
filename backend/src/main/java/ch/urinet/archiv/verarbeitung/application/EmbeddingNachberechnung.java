package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Startet die Nachberechnung der Embeddings eines Mandanten (Spec 02, Req 2.4): reiht den ersten Teil von
 * {@code EMBEDDING_ALLE} ein. Nicht in einer laufenden Transaktion aufrufen.
 */
@ApplicationScoped
public class EmbeddingNachberechnung {

    private final MandantKontext kontext;
    private final EmbeddingSpeichern speichern;

    public EmbeddingNachberechnung(MandantKontext kontext, EmbeddingSpeichern speichern) {
        this.kontext = kontext;
        this.speichern = speichern;
    }

    /** @return ID des Auftrags {@code EMBEDDING_ALLE} */
    public long starten(MandantCode mandant, boolean nurFehlende) {
        return kontext.mit(mandant, () -> speichern.nachberechnungEinreihen(nurFehlende));
    }
}
