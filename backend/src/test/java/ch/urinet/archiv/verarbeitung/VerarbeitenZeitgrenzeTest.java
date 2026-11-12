package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien.seite;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zeitüberschreitung mitten in OCRmyPDF (Aufgabe 2.6, Teil b): Der Adapter beendet den Container nach
 * {@code archiv.ocr.zeitgrenze}, der Versuch zählt als Fehlversuch, nichts wird abgelegt oder gespeichert, und der
 * nächste Versuch verarbeitet das Dokument genau einmal. Die Zeitgrenze ist kurz, das erste Dokument lang genug, dass
 * OCRmyPDF sie sicher überschreitet; ein Beobachter hält fest, dass dabei ein Container mit {@code ocrmypdf} lief.
 */
@QuarkusTest
@TestProfile(VerarbeitenZeitgrenzeTest.KurzeZeitgrenze.class)
class VerarbeitenZeitgrenzeTest {

    private static final int SEITEN = 48;

    public static class KurzeZeitgrenze implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.ocr.zeitgrenze", "PT15S", "archiv.auftrag.zeitgrenze.VERARBEITEN", "PT60S");
        }
    }

    @Inject
    VerarbeitungTestHilfe hilfe;

    @Inject
    AuftragTestHilfe auftraege;

    @ConfigProperty(name = "archiv.ocr.arbeitsverzeichnis")
    Path arbeitsverzeichnis;

    @Test
    void zeitueberschreitungInOcrMyPdfSchreibtNichtsUndDerNaechsteVersuchVerarbeitetEinmal() throws Exception {
        hilfe.vorbereiten();
        List<Quelle> dateien = new ArrayList<>();
        for (int nr = 1; nr <= SEITEN; nr++) {
            dateien.add(new Quelle(DateiRolle.ORIGINAL, nr, "jpg", "image/jpeg", OcrTestdateien.jpeg(seite(4,
                    "Langer Testbeleg Seite " + nr, "Zeile mit Text fuer die Texterkennung", "Noch eine Zeile Text",
                    HexFormat.of().formatHex(VerarbeitungTestHilfe.zufall(4))))));
        }
        UUID id = hilfe.anlegen("urinet", "SCAN", dateien);
        long auftrag = hilfe.einreihen("urinet", id);
        Set<Path> verzeichnisseVorher = verzeichnisse();

        AtomicBoolean ocrmypdfGesehen = new AtomicBoolean();
        AtomicBoolean fertig = new AtomicBoolean();
        CompletableFuture<Void> beobachter = CompletableFuture.runAsync(() -> {
            while (!fertig.get()) {
                if (container().stream().anyMatch(c -> c.contains("ocrmypdf"))) {
                    ocrmypdfGesehen.set(true);
                }
                schlafen();
            }
        });
        try {
            hilfe.abarbeiten();
        } finally {
            fertig.set(true);
            beobachter.get(30, TimeUnit.SECONDS);
        }

        assertThat(ocrmypdfGesehen).as("OCRmyPDF lief beim Abbruch").isTrue();
        assertThat(container()).as("kein Werkzeug-Container bleibt zurück").isEmpty();
        assertThat(verzeichnisse()).as("Arbeitsverzeichnisse gelöscht").isEqualTo(verzeichnisseVorher);
        AuftragTestHilfe.Zeile zeile = auftraege.zeile(auftrag);
        assertThat(zeile.status()).isEqualTo("OFFEN");
        assertThat(zeile.versuche()).isEqualTo(1);
        assertThat(zeile.fehler()).startsWith("fehlgeschlagen fehler-id=");
        Map<String, Object> dokument = hilfe.dokument("urinet", id);
        assertThat(dokument).containsEntry("status", "VERARBEITUNG").containsEntry("s3_pdfa", null)
                .containsEntry("seiten", null).containsEntry("volltext", null);
        assertThat(hilfe.versionen("urinet", VerarbeitungTestHilfe.pdfaSchluessel(id))).isEmpty();
        assertThat(hilfe.versionen("urinet", VerarbeitungTestHilfe.vorschauOrdner(id))).isEmpty();
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();

        // nächster Versuch mit einer Seite: schafft die Zeitgrenze
        auftraege.ausfuehren("delete from m_urinet.dokument_datei where dokument_id = '" + id + "' and nr > 1");
        auftraege.aendern(auftrag, "nicht_vor = now()");
        hilfe.abarbeiten();

        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
        dokument = hilfe.dokument("urinet", id);
        String pdfa = VerarbeitungTestHilfe.pdfaSchluessel(id);
        assertThat(dokument).containsEntry("status", "EINGANGSKORB").containsEntry("seiten", 1);
        assertThat(hilfe.versionen("urinet", pdfa)).containsExactly(pdfa + "@" + dokument.get("s3_version_pdfa"));
        assertThat(hilfe.versionen("urinet", VerarbeitungTestHilfe.vorschauOrdner(id))).hasSize(1);
        assertThat(hilfe.auftragStatus("KI_VORSCHLAG", id)).containsExactly("FERTIG");
    }

    /** Laufende Werkzeug-Container des Adapters ({@code archiv-ocr-<uuid>}) mit vollem Befehl. */
    private static List<String> container() {
        try {
            Process ps = new ProcessBuilder("docker", "ps", "--no-trunc", "--filter", "name=archiv-ocr-",
                    "--format", "{{.Names}} {{.Command}}").redirectErrorStream(true).start();
            String ausgabe = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!ps.waitFor(10, TimeUnit.SECONDS) || ps.exitValue() != 0) {
                throw new IllegalStateException("docker ps gescheitert: " + ausgabe);
            }
            return ausgabe.lines().filter(z -> !z.isBlank()).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Set<Path> verzeichnisse() throws IOException {
        if (!Files.isDirectory(arbeitsverzeichnis)) {
            return Set.of();
        }
        try (Stream<Path> inhalt = Files.list(arbeitsverzeichnis)) {
            return inhalt.collect(Collectors.toSet());
        }
    }

    private static void schlafen() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
