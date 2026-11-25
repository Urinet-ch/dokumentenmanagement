package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.AblageService;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import ch.urinet.archiv.verarbeitung.application.ArchivierenHandler;
import ch.urinet.archiv.verarbeitung.application.ArchivierungSpeichern;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.DokumentArchivierungSperre;
import ch.urinet.archiv.verarbeitung.application.EmbeddingSpeichern;
import ch.urinet.archiv.verarbeitung.application.InhaberBenachrichtigung;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pflichten des {@link ArchivierenHandler} aus design.md «Pflichten der Handler» (Aufgabe 2.6, Review PR #266, M1 und
 * S2): ohne Anspruch schreibt er nichts, und eine Wiederholung auf einem schon archivierten oder einem anderen Dokument
 * ändert nichts ausser dem Aufräumen von {@code eingang/}. Nur synthetische Inhalte (Req 16.15).
 */
@QuarkusTest
class ArchivierenHandlerPflichtenTest {

    private static final String URINET = "urinet";
    private static final MandantCode MANDANT = new MandantCode(URINET);

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    ArchivierenHandler handler;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantBucket buckets;

    @Inject
    AblagePort ablage;

    @Inject
    AblageService ablageService;

    @Inject
    ArchivierungSpeichern speichern;

    @Inject
    InhaberBenachrichtigung benachrichtigung;

    @Inject
    DokumentArchivierungSperre sperre;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final List<UUID> dokumente = new ArrayList<>();

    @BeforeEach
    void vorbereiten() throws Exception {
        auftraege.vorbereiten();
        auftraege.ausfuehren("delete from public.auftrag where typ in ('ARCHIVIEREN', 'VERARBEITEN', 'KI_VORSCHLAG', "
                + "'EMBEDDING', 'EMBEDDING_ALLE')");
        buckets.einrichten(MANDANT.bucket(bucketPraefix));
    }

    @AfterEach
    void aufraeumen() throws Exception {
        for (UUID id : dokumente) {
            auftraege.ausfuehren("delete from public.auftrag where nutzlast ->> 'dokumentId' = '" + id + "'");
            ablage.loeschenEingang(MANDANT, VerarbeitungTestHilfe.JAHR, id);
        }
        Testdokumente.loeschen(datenbank.get(), URINET, dokumente);
        dokumente.clear();
    }

    @Test
    void ohneAnspruchHaeltDerHandlerNichtsFestUndLoeschtEingangNicht() throws Exception {
        UUID id = anlegen("ARCHIVIERUNG");
        String vorher = zeile(id);
        Map<String, String[]> dateien = hilfe.dateien(URINET, id);
        List<String> eingang = hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id));

        assertThatThrownBy(() -> ausfuehren(fremd(id))).hasRootCauseInstanceOf(AuftragService.AnspruchVerloren.class);

        assertThat(zeile(id)).isEqualTo(vorher).contains("\"status\":\"ARCHIVIERUNG\"")
                .contains("\"sperre_bis\":null");
        assertThat(hilfe.dateien(URINET, id)).containsOnlyKeys(dateien.keySet())
                .allSatisfy((schluessel, datei) -> assertThat(datei).containsExactly(dateien.get(schluessel)));
        assertThat(eingang).isNotEmpty();
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isEqualTo(eingang);
        assertThat(hilfe.auftraege(EmbeddingSpeichern.EMBEDDING.wert(), id)).isEmpty();

        // Gegenprobe: mit Anspruch archiviert derselbe Stand, der abgebrochene Lauf hat nichts verbaut
        long auftrag = einreihen(id);
        hilfe.abarbeiten();
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
        assertThat(zeile(id)).contains("\"status\":\"ARCHIVIERT\"");
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isEmpty();
    }

    @Test
    void ohneAnspruchSetztEinAbbruchKeinenArchivierungsfehler() throws Exception {
        UUID id = anlegen("ARCHIVIERUNG");
        hilfe.pruefsummeVerfaelschen(URINET, id);
        String vorher = zeile(id);
        List<String> eingang = hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id));

        assertThatThrownBy(() -> ausfuehren(fremd(id))).hasRootCauseInstanceOf(AuftragService.AnspruchVerloren.class);

        assertThat(zeile(id)).isEqualTo(vorher).contains("\"status\":\"ARCHIVIERUNG\"").contains("\"fehler\":null");
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isEqualTo(eingang);
    }

    @Test
    void einSchonArchiviertesDokumentRaeumtNurEingangAuf() throws Exception {
        UUID id = anlegen("ARCHIVIERT");
        String vorher = zeile(id);
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isNotEmpty();

        long auftrag = einreihen(id);
        hilfe.abarbeiten();

        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
        assertThat(zeile(id)).isEqualTo(vorher);
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isEmpty();
        assertThat(hilfe.versionen(URINET, archivOrdner(id))).isEmpty();
        assertThat(hilfe.auftraege(EmbeddingSpeichern.EMBEDDING.wert(), id)).isEmpty();
    }

    @Test
    void einVerworfenesDokumentBleibtUnveraendert() throws Exception {
        UUID id = anlegen("VERWORFEN");
        String vorher = zeile(id);
        List<String> eingang = hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id));

        long auftrag = einreihen(id);
        hilfe.abarbeiten();

        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
        assertThat(zeile(id)).isEqualTo(vorher);
        assertThat(eingang).isNotEmpty();
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isEqualTo(eingang);
        assertThat(hilfe.versionen(URINET, archivOrdner(id))).isEmpty();
    }

    /** @req-6.3: Zwei parallele Handlerläufe kopieren ein Dokument nur einmal. */
    @Test
    void paralleleHandlerSerialisierenArchivierung() throws Exception {
        UUID id = anlegen("ARCHIVIERUNG");
        Auftrag erster = beansprucht(id);
        Auftrag zweiter = beansprucht(id);
        CountDownLatch ersteSperre = new CountDownLatch(1);
        CountDownLatch zweiterGestartet = new CountDownLatch(1);
        AtomicInteger aufrufe = new AtomicInteger();
        DokumentArchivierungSperre beobachtet = (mandant, dokument) -> {
            if (aufrufe.incrementAndGet() == 1) {
                var gehalten = sperre.sperren(mandant, dokument);
                ersteSperre.countDown();
                if (!zweiterGestartet.await(5, TimeUnit.SECONDS)) {
                    gehalten.close();
                    throw new IllegalStateException("Zweiter Handler nicht gestartet");
                }
                return gehalten;
            }
            zweiterGestartet.countDown();
            return sperre.sperren(mandant, dokument);
        };
        ArchivierenHandler testHandler = handlerMit(beobachtet);
        CompletableFuture<Void> lauf1 = starten(testHandler, erster);
        assertThat(ersteSperre.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> lauf2 = starten(testHandler, zweiter);
        CompletableFuture.allOf(lauf1, lauf2).get(30, TimeUnit.SECONDS);

        assertThat(aufrufe).hasValue(2);
        assertThat(zeile(id)).contains("\"status\":\"ARCHIVIERT\"");
        assertThat(hilfe.versionen(URINET, archivOrdner(id))).hasSize(1);
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.eingangOrdner(id))).isEmpty();
    }

    /** @req-6.3: Auch bei verlorenem Anspruch wird die Sperre freigegeben. */
    @Test
    void ausnahmeGibtHandlerSperreFrei() throws Exception {
        UUID id = anlegen("ARCHIVIERUNG");
        AtomicBoolean freigegeben = new AtomicBoolean();
        DokumentArchivierungSperre beobachtet = (mandant, dokument) -> {
            var gehalten = sperre.sperren(mandant, dokument);
            return () -> {
                gehalten.close();
                freigegeben.set(true);
            };
        };
        assertThatThrownBy(() -> starten(handlerMit(beobachtet), fremd(id)).get())
                .hasRootCauseInstanceOf(AuftragService.AnspruchVerloren.class);
        assertThat(freigegeben).isTrue();
        try (DokumentArchivierungSperre.Sperre erneut = sperre.sperren(MANDANT, id)) {
            assertThat(erneut).isNotNull();
        }
    }

    /** @req-6.3: Ein Freigabefehler macht eine abgeschlossene Archivierung nicht zum Fehlversuch. */
    @Test
    void freigabefehlerNachErfolgWirftNicht() throws Exception {
        UUID id = anlegen("ARCHIVIERUNG");
        DokumentArchivierungSperre fehlerhaft = (mandant, dokument) -> {
            var gehalten = sperre.sperren(mandant, dokument);
            return () -> {
                gehalten.close();
                throw new IllegalStateException("synthetischer Freigabefehler");
            };
        };
        starten(handlerMit(fehlerhaft), beansprucht(id)).get(30, TimeUnit.SECONDS);
        assertThat(zeile(id)).contains("\"status\":\"ARCHIVIERT\"");
        assertThat(hilfe.versionen(URINET, archivOrdner(id))).hasSize(1);
    }

    /** Dokument mit Original unter {@code eingang/}, ohne PDF/A ({@code pdfa_fehlt}), damit nur das Original zählt. */
    private UUID anlegen(String status) throws Exception {
        byte[] pdf = OcrTestdateien.textPdf("Archivierung " + HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4)));
        UUID id = hilfe.anlegen(URINET, "SCAN", status, List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf",
                "application/pdf", pdf)));
        dokumente.add(id);
        auftraege.ausfuehren("update " + MANDANT.schema() + ".dokument set pdfa_fehlt = true where id = '" + id + "'");
        return id;
    }

    private String zeile(UUID id) throws Exception {
        return String.valueOf(auftraege.wertAus("select row_to_json(d)::text from " + MANDANT.schema()
                + ".dokument d where id = '" + id + "'"));
    }

    private long einreihen(UUID id) throws Exception {
        return auftraege.rohEinreihen(URINET, ArchivierenHandler.TYP,
                "{\"" + ArchivierenHandler.DOKUMENT_ID + "\":\"" + id + "\"}");
    }

    private Auftrag beansprucht(UUID id) throws Exception {
        long auftrag = einreihen(id);
        auftraege.aendern(auftrag, "status = 'LAEUFT', versuche = 1, lebenszeichen = now()");
        return new Auftrag(auftrag, MANDANT, ArchivierenHandler.TYP,
                new Nutzlast(Map.of(ArchivierenHandler.DOKUMENT_ID, id)), 1);
    }

    private ArchivierenHandler handlerMit(DokumentArchivierungSperre testSperre) {
        return new ArchivierenHandler(speichern, ablageService, ablage, benachrichtigung, testSperre);
    }

    private CompletableFuture<Void> starten(ArchivierenHandler testHandler, Auftrag auftrag) {
        return CompletableFuture.runAsync(() -> kontext.mit(MANDANT, () -> {
            try {
                testHandler.ausfuehren(auftrag);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }), threads);
    }

    /** Ein Auftrag, den kein Arbeiter hält: {@code anspruchSichern} schlägt fehl. */
    private static Auftrag fremd(UUID id) {
        return new Auftrag(Long.MAX_VALUE, MANDANT, ArchivierenHandler.TYP,
                new Nutzlast(Map.of(ArchivierenHandler.DOKUMENT_ID, id)), 1);
    }

    private void ausfuehren(Auftrag auftrag) throws Exception {
        CompletableFuture.runAsync(() -> kontext.mit(MANDANT, () -> {
            try {
                handler.ausfuehren(auftrag);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }), threads).get();
    }

    private static String archivOrdner(UUID id) {
        return DateiSchluessel.ordner(Ablageort.ARCHIV, VerarbeitungTestHilfe.JAHR, id);
    }
}
