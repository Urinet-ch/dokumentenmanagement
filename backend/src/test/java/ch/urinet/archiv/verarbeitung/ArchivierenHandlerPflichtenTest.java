package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.application.AblagePort;
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
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.EmbeddingSpeichern;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
