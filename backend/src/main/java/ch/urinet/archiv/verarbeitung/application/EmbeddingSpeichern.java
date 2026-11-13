package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kurze Transaktionen von {@link EmbeddingHandler} und {@link EmbeddingAlleHandler} (design.md «Pflichten der Handler»,
 * (2)): Jede schreibende Methode sichert zuerst den Anspruch auf den Auftrag. Der Aufruf des Embedding-Modells liegt
 * ausserhalb jeder Transaktion. Eigene Klasse, weil {@code @Transactional} nur über den Proxy wirkt.
 */
@ApplicationScoped
public class EmbeddingSpeichern {

    public static final AuftragTyp EMBEDDING = new AuftragTyp("EMBEDDING");
    public static final AuftragTyp EMBEDDING_ALLE = new AuftragTyp("EMBEDDING_ALLE");
    /** Nutzlast von {@code EMBEDDING_ALLE}: letzte ID des vorigen Teils (fehlt im ersten Teil). */
    public static final String NACH = "nach";
    /** Nutzlast von {@code EMBEDDING_ALLE}: nur Dokumente ohne Abschnitte oder mit Abschnitten ohne Vektor. */
    public static final String NUR_FEHLENDE = "nurFehlende";
    /** Dokumente je Teil von {@code EMBEDDING_ALLE}; ein Teil reiht so viele {@code EMBEDDING} in einer Transaktion ein. */
    static final int TEIL = 500;

    private final ChunkPort chunks;
    private final AuftragService auftraege;

    public EmbeddingSpeichern(ChunkPort chunks, AuftragService auftraege) {
        this.chunks = chunks;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<ChunkPort.Stand> laden(UUID id) {
        return chunks.laden(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean ersetzen(Auftrag auftrag, UUID id, List<ChunkPort.Eingebettet> eingebettet) {
        auftraege.anspruchSichern(auftrag);
        return chunks.ersetzen(id, eingebettet);
    }

    /** Erster Teil der Nachberechnung (Req 2.4); Mandant aus dem Kontext. @return ID des Auftrags */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long nachberechnungEinreihen(boolean nurFehlende) {
        return auftraege.einreihen(EMBEDDING_ALLE, alle(Optional.empty(), nurFehlende));
    }

    /**
     * Ein Teil der Nachberechnung: je archiviertem Dokument ein {@code EMBEDDING}, bei einem vollen Teil der nächste
     * Teil, alles in einer Transaktion.
     *
     * @return Zahl der eingereihten {@code EMBEDDING}
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int teilEinreihen(Auftrag auftrag, Optional<UUID> nach, boolean nurFehlende) {
        auftraege.anspruchSichern(auftrag);
        List<UUID> ids = chunks.zurNachberechnung(nach, TEIL, nurFehlende);
        for (UUID id : ids) {
            auftraege.einreihen(EMBEDDING, einzeln(id));
        }
        if (ids.size() == TEIL) {
            auftraege.einreihen(EMBEDDING_ALLE, alle(Optional.of(ids.getLast()), nurFehlende));
        }
        return ids.size();
    }

    static Nutzlast einzeln(UUID id) {
        return new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id));
    }

    static Nutzlast alle(Optional<UUID> nach, boolean nurFehlende) {
        Map<String, Object> werte = new HashMap<>();
        nach.ifPresent(id -> werte.put(NACH, id));
        werte.put(NUR_FEHLENDE, nurFehlende);
        return new Nutzlast(werte);
    }
}
