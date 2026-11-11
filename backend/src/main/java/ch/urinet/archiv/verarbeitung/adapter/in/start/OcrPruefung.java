package ch.urinet.archiv.verarbeitung.adapter.in.start;

import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrEinstellungen;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Startprüfung des OCR-Adapters (design.md «Verarbeitung»; tasks.md 2.6): Ohne Stub bricht der Start ab, wenn
 * {@code archiv.ocr.zeitgrenze} nicht unter der Zeitgrenze von {@code VERARBEITEN} liegt oder wenn in {@code prod} der
 * Container-Modus ({@code archiv.ocr.container-image}, nur Tests und Profil {@code lokal}) gesetzt ist. Fehlen im
 * direkten Aufruf Werkzeuge im {@code PATH}, warnt die Worker-Rolle nur: Das Image prüft die Werkzeuge beim Bau, und die
 * API-Rolle braucht sie nicht.
 */
@ApplicationScoped
public class OcrPruefung {

    private static final Logger LOG = Logger.getLogger(OcrPruefung.class);

    static final List<String> WERKZEUGE = List.of("ocrmypdf", "tesseract", "gs", "img2pdf", "heif-convert",
            "pdftotext", "pdftoppm");

    void beimStart(@Observes StartupEvent ereignis,
                   @ConfigProperty(name = "archiv.ocr.stub") boolean stub,
                   @ConfigProperty(name = "archiv.auftrag.zeitgrenze.VERARBEITEN") Duration verarbeiten,
                   @ConfigProperty(name = "archiv.rolle") String rolle,
                   Instance<OcrEinstellungen> einstellungen) {
        if (stub) {
            return;
        }
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        OcrEinstellungen ocr = einstellungen.get();
        pruefe(ocr, verarbeiten, config.getProfiles().contains("prod") || LaunchMode.current() == LaunchMode.NORMAL);
        if (worker(rolle) && ocr.containerImage().isEmpty()) {
            List<String> fehlend = fehlendeWerkzeuge(ocr.pfad());
            if (!fehlend.isEmpty()) {
                LOG.warnf("OCR-Werkzeuge fehlen im PATH (%s): %s; Verarbeitung scheitert bis zum Worker-Image",
                        ocr.pfad(), String.join(", ", fehlend));
            }
        }
    }

    static void pruefe(OcrEinstellungen ocr, Duration verarbeiten, boolean prod) {
        OcrEinstellungen.pruefeZeitgrenze(ocr.zeitgrenze(), verarbeiten);
        if (prod && ocr.containerImage().isPresent()) {
            throw new IllegalStateException("archiv.ocr.container-image ist in prod nicht zulässig; die Werkzeuge "
                    + "gehören ins Image (Dockerfile.jvm)");
        }
    }

    static List<String> fehlendeWerkzeuge(String pfad) {
        List<Path> verzeichnisse = Arrays.stream(pfad.split(":")).filter(s -> !s.isBlank()).map(Path::of).toList();
        return WERKZEUGE.stream()
                .filter(w -> verzeichnisse.stream().noneMatch(v -> Files.isExecutable(v.resolve(w))))
                .toList();
    }

    private static boolean worker(String rolle) {
        return Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }
}
