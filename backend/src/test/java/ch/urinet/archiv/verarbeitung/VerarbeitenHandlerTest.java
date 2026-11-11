package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.Verarbeitungsergebnis;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import ch.urinet.archiv.verarbeitung.application.VerarbeitungSpeichern;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien.seite;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pflichten des {@link VerarbeitenHandler} aus design.md «Pflichten der Handler» (Aufgabe 2.6, Teil b): idempotent,
 * Anspruch gesichert, Mandant aus dem Auftrag, Reste abgebrochener Läufe bereinigt, Generator-Dokumente erst mit 4.7.
 */
@QuarkusTest
class VerarbeitenHandlerTest {

    private static final String URINET = "urinet";

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    VerarbeitungSpeichern speichern;

    @Inject
    MandantKontext kontext;

    @Inject
    AblagePort ablage;

    private final ExecutorService threads = Executors.newCachedThreadPool();

    @BeforeEach
    void vorbereiten() throws Exception {
        hilfe.vorbereiten();
    }

    @Test
    void resteEinesAbgebrochenenLaufsWerdenBereinigtUndEinZweiterAuftragAendertNichts() throws Exception {
        UUID id = hilfe.anlegen(URINET, "SCAN", "VERARBEITUNG", foto("Rest eines Absturzes"));
        MandantCode mandant = new MandantCode(URINET);
        byte[] alt = OcrTestdateien.textPdf("veraltete Fassung");
        ablage.ablegenEingang(mandant, DateiSchluessel.pdfa(VerarbeitungTestHilfe.JAHR, id),
                new ByteArrayInputStream(alt), alt.length);
        Path bild = Files.createTempFile("vorschau-", ".png");
        try {
            Files.write(bild, OcrTestdateien.png(seite(0, "alt")));
            ablage.ablegenVorschau(mandant, id, 5, bild);
        } finally {
            Files.deleteIfExists(bild);
        }

        hilfe.einreihen(URINET, id);
        hilfe.abarbeiten();

        Map<String, Object> dokument = hilfe.dokument(URINET, id);
        String pdfa = VerarbeitungTestHilfe.pdfaSchluessel(id);
        assertThat(dokument).containsEntry("status", "VERARBEITUNG").containsEntry("seiten", 1);
        assertThat(hilfe.versionen(URINET, pdfa)).containsExactly(pdfa + "@" + dokument.get("s3_version_pdfa"));
        assertThat(hilfe.objektversionen(URINET, VerarbeitungTestHilfe.vorschauOrdner(id)))
                .extracting(ObjectVersion::key).containsExactly(VerarbeitungTestHilfe.vorschauOrdner(id) + "1.png");
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).hasSize(1);

        long zweiter = hilfe.einreihen(URINET, id);
        hilfe.abarbeiten();

        assertThat(auftraege.zeile(zweiter).status()).isEqualTo("FERTIG");
        assertThat(hilfe.dokument(URINET, id)).isEqualTo(dokument);
        assertThat(hilfe.versionen(URINET, pdfa)).containsExactly(pdfa + "@" + dokument.get("s3_version_pdfa"));
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).hasSize(1);
        assertThat(TestHandler.KI_DOKUMENTE).containsOnlyOnce(id);
    }

    @Test
    void ohneAnspruchSchreibtDerHandlerNichts() throws Exception {
        UUID id = hilfe.anlegen(URINET, "SCAN", foto("ohne Anspruch"));
        Map<String, Object> vorher = hilfe.dokument(URINET, id);
        Auftrag fremd = new Auftrag(Long.MAX_VALUE, new MandantCode(URINET), VerarbeitenHandler.TYP,
                new Nutzlast(Map.of(VerarbeitenHandler.DOKUMENT_ID, id)), 1);
        var ergebnis = new Verarbeitungsergebnis(HexFormat.of().formatHex(new byte[32]),
                VerarbeitungTestHilfe.pdfaSchluessel(id), "v1", false, null, 1, "Text");

        assertThatThrownBy(() -> imMandanten(URINET, () -> speichern.beginnen(fremd, id, VerarbeitungTestHilfe.JAHR)))
                .hasRootCauseInstanceOf(AuftragService.AnspruchVerloren.class);
        assertThatThrownBy(() -> imMandanten(URINET, () -> speichern.ergebnisSpeichern(fremd, id, ergebnis)))
                .hasRootCauseInstanceOf(AuftragService.AnspruchVerloren.class);

        assertThat(hilfe.dokument(URINET, id)).isEqualTo(vorher);
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();
    }

    @Test
    void derMandantKommtNurAusDemAuftrag() throws Exception {
        UUID id = hilfe.anlegen(URINET, "SCAN", foto("falscher Mandant"));
        Map<String, Object> vorher = hilfe.dokument(URINET, id);
        long auftrag = auftraege.rohEinreihen("vhf-gsk", VerarbeitenHandler.TYP,
                "{\"dokumentId\":\"" + id + "\"}");

        hilfe.abarbeiten();

        AuftragTestHilfe.Zeile zeile = auftraege.zeile(auftrag);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).startsWith("fehlgeschlagen fehler-id=");
        assertThat(hilfe.dokument(URINET, id)).isEqualTo(vorher);
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.pdfaSchluessel(id))).isEmpty();
    }

    @Test
    void generatorDokumenteUndOhneKiFolgenMitAufgabe47() throws Exception {
        UUID generator = hilfe.anlegen(URINET, "GENERATOR", foto("Generator"));
        UUID ohneKi = hilfe.anlegen(URINET, "UPLOAD", foto("ohne KI"));
        long a = hilfe.einreihen(URINET, generator);
        long b = auftraege.rohEinreihen(URINET, VerarbeitenHandler.TYP,
                "{\"dokumentId\":\"" + ohneKi + "\",\"ohneKi\":true}");

        hilfe.abarbeiten();

        for (long auftrag : List.of(a, b)) {
            assertThat(auftraege.zeile(auftrag).status()).isEqualTo("OFFEN");
            assertThat(auftraege.zeile(auftrag).versuche()).isEqualTo(1);
        }
        for (UUID id : List.of(generator, ohneKi)) {
            assertThat(hilfe.dokument(URINET, id)).containsEntry("status", "NEU").containsEntry("s3_pdfa", null);
        }
    }

    @Test
    void einDokumentImEingangskorbBleibtUnveraendert() throws Exception {
        UUID id = hilfe.anlegen(URINET, "SCAN", "EINGANGSKORB", foto("schon im Eingangskorb"));
        Map<String, Object> vorher = hilfe.dokument(URINET, id);
        long auftrag = hilfe.einreihen(URINET, id);

        hilfe.abarbeiten();

        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
        assertThat(hilfe.dokument(URINET, id)).isEqualTo(vorher);
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();
        assertThat(hilfe.versionen(URINET, VerarbeitungTestHilfe.vorschauOrdner(id))).isEmpty();
    }

    private <T> T imMandanten(String mandant, Supplier<T> arbeit) throws Exception {
        return CompletableFuture.supplyAsync(() -> kontext.mit(new MandantCode(mandant), arbeit), threads).get();
    }

    private static List<Quelle> foto(String text) {
        return List.of(new Quelle(DateiRolle.ORIGINAL, 1, "jpg", "image/jpeg", OcrTestdateien.jpeg(seite(2, "Testbeleg",
                text, HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4))))));
    }
}
