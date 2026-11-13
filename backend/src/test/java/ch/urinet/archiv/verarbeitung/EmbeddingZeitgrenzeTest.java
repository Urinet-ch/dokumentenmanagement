package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ki.TestEmbeddingPort;
import ch.urinet.archiv.ki.adapter.out.persistence.JdbcChunkSpeicher;
import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.ki.domain.Chunker;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.EmbeddingHandler;
import ch.urinet.archiv.verarbeitung.application.EmbeddingSpeichern;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 02, Aufgabe 6.3 (Nachbesserung Review PR #125): Die Transaktion, in der {@link EmbeddingHandler} die Abschnitte
 * ersetzt, hat die Zeitgrenze von {@code EMBEDDING} ({@code archiv.auftrag.zeitgrenze.EMBEDDING}, 10 min) statt der
 * Standardgrenze; danach gilt für den Thread wieder die Standardgrenze, auch wenn das Schreiben scheitert. Gemessen in
 * der Transaktion von {@code EmbeddingSpeichern.ersetzen} ({@code @Transactional(REQUIRES_NEW)}) über einen
 * {@link ChunkPort}, der nichts schreibt.
 */
@QuarkusTest
class EmbeddingZeitgrenzeTest {

    private static final int ZEITGRENZE_SEKUNDEN = 600;

    @Inject
    EmbeddingHandler handler;

    @Inject
    TestEmbeddingPort port;

    @Inject
    TransactionManager transaktionen;

    private final AtomicInteger gemessen = new AtomicInteger(-1);
    private volatile boolean scheitern;

    @BeforeEach
    void vorbereiten() {
        port.setzen(TestEmbeddingPort.Ersatz.STUB);
        // QuarkusMock verlangt einen Untertyp der Bean-Klasse
        QuarkusMock.installMockForType(new JdbcChunkSpeicher(null, null) {
            @Override
            public Optional<ChunkPort.Stand> laden(UUID dokumentId) {
                return Optional.of(new ChunkPort.Stand(dokumentId, true,
                        new Chunker.Quelle("Probe", "Offerte", "Muster AG", "Text der Probe\f", 1, false)));
            }

            @Override
            public boolean ersetzen(UUID dokumentId, List<ChunkPort.Eingebettet> chunks) {
                gemessen.set(zeitgrenze());
                if (scheitern) {
                    throw new IllegalStateException("Probe");
                }
                return false;
            }

            @Override
            public List<UUID> zurNachberechnung(Optional<UUID> nach, int anzahl, boolean nurFehlende) {
                return List.of();
            }
        }, JdbcChunkSpeicher.class);
    }

    @AfterEach
    void zuruecksetzen() {
        port.setzen(null);
    }

    @Test
    void ersetzenLaeuftMitDerZeitgrenzeVonEmbedding() throws Exception {
        handler.ausfuehren(auftrag());
        assertThat(gemessen.get()).isEqualTo(ZEITGRENZE_SEKUNDEN);
        assertThat(standardgrenze()).isNotEqualTo(ZEITGRENZE_SEKUNDEN);
    }

    @Test
    void auchNachEinemFehlerGiltWiederDieStandardgrenze() {
        scheitern = true;
        assertThatThrownBy(() -> handler.ausfuehren(auftrag())).hasMessageContaining("Probe");
        assertThat(gemessen.get()).isEqualTo(ZEITGRENZE_SEKUNDEN);
        assertThat(standardgrenze()).isNotEqualTo(ZEITGRENZE_SEKUNDEN);
    }

    private int zeitgrenze() {
        try {
            return ((TransactionImple) transaktionen.getTransaction()).getTimeout();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Zeitgrenze einer neuen Transaktion auf diesem Thread, also nach dem Zurücksetzen im Handler. */
    private int standardgrenze() {
        try {
            transaktionen.begin();
            try {
                return zeitgrenze();
            } finally {
                transaktionen.rollback();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Auftrag auftrag() {
        return new Auftrag(1L, new MandantCode("urinet"), EmbeddingSpeichern.EMBEDDING,
                new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, UUID.randomUUID())), 0);
    }
}
