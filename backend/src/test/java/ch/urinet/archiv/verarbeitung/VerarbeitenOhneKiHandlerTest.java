package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.application.KiVorschlagHandler;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien.seite;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ohne Handler für {@code KI_VORSCHLAG} (ausgeschlossen) geht das Dokument nach der Verarbeitung direkt in den
 * Eingangskorb, und es entsteht kein Auftrag, den niemand bearbeitet (VerarbeitungSpeichern, Rückfall).
 */
@Tag("start-1")
@QuarkusTest
@TestProfile(VerarbeitenOhneKiHandlerTest.OhneKiHandler.class)
class VerarbeitenOhneKiHandlerTest {

    public static class OhneKiHandler implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.arc.exclude-types", KiVorschlagHandler.class.getName());
        }
    }

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Test
    void ohneKiHandlerDirektInDenEingangskorb() throws Exception {
        hilfe.vorbereiten();
        UUID id = hilfe.anlegen("urinet", "SCAN", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "jpg", "image/jpeg",
                OcrTestdateien.jpeg(seite(0, "Beleg ohne KI", HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4)))))));
        hilfe.einreihen("urinet", id);

        hilfe.abarbeiten();

        Map<String, Object> dokument = hilfe.dokument("urinet", id);
        assertThat(dokument).containsEntry("status", "EINGANGSKORB").containsEntry("pdfa_fehlt", false)
                .containsEntry("seiten", 1);
        assertThat(dokument.get("s3_pdfa")).isNotNull();
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();
    }
}
