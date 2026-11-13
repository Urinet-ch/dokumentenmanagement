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
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Transaktionen von {@link EmbeddingHandler} und {@link EmbeddingAlleHandler} (design.md «Pflichten der Handler», (2)):
 * Jede schreibende Methode sichert den Anspruch auf den Auftrag und hält ihn bis zum Commit. {@link #ersetzen} sichert
 * ihn erst nach dem Schreiben der Abschnitte, damit Lebenszeichen und Aufräumer während des langen Einfügens (HNSW)
 * nicht auf die Zeile warten; hält er dann nicht mehr, rollt alles zurück. Der Aufruf des Embedding-Modells liegt
 * ausserhalb jeder Transaktion. Eigene Klasse, weil {@code @Transactional} nur über den Proxy wirkt.
 */
@ApplicationScoped
public class EmbeddingSpeichern {

    public static final AuftragTyp EMBEDDING = new AuftragTyp("EMBEDDING");
    public static final AuftragTyp EMBEDDING_ALLE = new AuftragTyp("EMBEDDING_ALLE");
    /** Nutzlast von {@code EMBEDDING_ALLE}: letzte ID des vorigen Teils (fehlt im ersten Teil). */
    public static final String NACH = "nach";
    /**
     * Nutzlast der Aufträge, die ein Teil von {@code EMBEDDING_ALLE} einreiht: ID dieses Teils. Daran erkennt ein
     * erneuter Lauf desselben Teils, dass er schon eingereiht hat, und eine neue Nachberechnung eine laufende Kette.
     */
    public static final String VORGAENGER = "vorgaenger";
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

    /**
     * Ersetzt die Abschnitte und sichert danach den Anspruch (bis zum Commit). Die Zeitgrenze der Transaktion setzt der
     * Handler.
     *
     * @return {@code false}, wenn die vorhandenen Abschnitte geblieben sind (nichts geschrieben)
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean ersetzen(Auftrag auftrag, UUID id, List<ChunkPort.Eingebettet> eingebettet)
            throws InterruptedException {
        if (!chunks.ersetzen(id, eingebettet)) {
            return false;
        }
        auftraege.anspruchSichern(auftrag);
        return true;
    }

    /**
     * Erster Teil der Nachberechnung (Req 2.4); Mandant aus dem Kontext. Läuft für den Mandanten schon eine
     * Nachberechnung (ein Teil {@code OFFEN} oder {@code LAEUFT}, oder ein von ihr eingereihtes {@code EMBEDDING} noch
     * offen), reiht der Aufruf nichts ein und nennt deren Auftrag; die Sperre auf den Typ ordnet gleichzeitige Aufrufe.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Start nachberechnungEinreihen(boolean nurFehlende) {
        OptionalLong laufend = auftraege.offen(EMBEDDING_ALLE, Optional.empty(), true);
        if (laufend.isEmpty()) {
            laufend = auftraege.offen(EMBEDDING, Optional.of(VORGAENGER), false);
        }
        if (laufend.isPresent()) {
            return new Start(laufend.getAsLong(), false);
        }
        return new Start(auftraege.einreihen(EMBEDDING_ALLE, alle(Optional.empty(), nurFehlende, Optional.empty())),
                true);
    }

    /**
     * Ein Teil der Nachberechnung: je archiviertem Dokument ein {@code EMBEDDING}, bei einem vollen Teil der nächste
     * Teil, alles in einer Transaktion. Idempotent: Hat ein früherer Lauf desselben Teils schon committet (seine
     * Aufträge tragen {@link #VORGAENGER}), schreibt der Teil nichts; die Prüfung liegt unter dem Anspruch, also unter
     * der Sperre der Auftragszeile.
     *
     * @return Zahl der eingereihten {@code EMBEDDING}; leer, wenn ein früherer Lauf schon eingereiht hat
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public OptionalInt teilEinreihen(Auftrag auftrag, Optional<UUID> nach, boolean nurFehlende) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER)) {
            return OptionalInt.empty();
        }
        List<UUID> ids = chunks.zurNachberechnung(nach, TEIL, nurFehlende);
        for (UUID id : ids) {
            auftraege.einreihen(EMBEDDING, new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id,
                    VORGAENGER, auftrag.id())));
        }
        if (ids.size() == TEIL) {
            auftraege.einreihen(EMBEDDING_ALLE, alle(Optional.of(ids.getLast()), nurFehlende,
                    Optional.of(auftrag.id())));
        }
        return OptionalInt.of(ids.size());
    }

    /** Nutzlast eines einzelnen {@code EMBEDDING} (nach der Archivierung oder einer Änderung der Kopfzeile). */
    public static Nutzlast einzeln(UUID id) {
        return new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id));
    }

    static Nutzlast alle(Optional<UUID> nach, boolean nurFehlende, Optional<Long> vorgaenger) {
        Map<String, Object> werte = new HashMap<>();
        nach.ifPresent(id -> werte.put(NACH, id));
        vorgaenger.ifPresent(id -> werte.put(VORGAENGER, id));
        werte.put(NUR_FEHLENDE, nurFehlende);
        return new Nutzlast(werte);
    }

    /**
     * Ergebnis von {@link #nachberechnungEinreihen}.
     *
     * @param auftrag ID des neuen ersten Teils bzw. eines Auftrags der laufenden Nachberechnung
     * @param neu     {@code false}, wenn schon eine Nachberechnung lief (nichts eingereiht)
     */
    public record Start(long auftrag, boolean neu) {
    }
}
