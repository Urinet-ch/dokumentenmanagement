package ch.urinet.archiv.verarbeitung.adapter.out;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrEinstellungen;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrMyPdfAdapter;
import ch.urinet.archiv.verarbeitung.adapter.out.stub.OcrStub;
import ch.urinet.archiv.verarbeitung.application.OcrPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und OCRmyPDF-Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class OcrAuswahl {

    @Produces
    @ApplicationScoped
    public OcrPort ocr(@ConfigProperty(name = "archiv.ocr.stub") boolean stub, OcrStub dev,
                       Instance<OcrEinstellungen> einstellungen) {
        return stub ? dev : new OcrMyPdfAdapter(einstellungen.get());
    }

    /** Für die Startprüfung der Port-Auswahl: OCRmyPDF ist der echte Adapter (Aufgabe 2.6). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ocr.stub") boolean stub) {
        return new PortAuswahl("OcrPort", stub, true, Optional.empty());
    }

    @Produces
    @Singleton
    public OcrEinstellungen einstellungen(
            @ConfigProperty(name = "archiv.ocr.zeitgrenze") Duration zeitgrenze,
            @ConfigProperty(name = "archiv.ocr.jobs") int jobs,
            @ConfigProperty(name = "archiv.ocr.arbeitsverzeichnis") Path arbeitsverzeichnis,
            @ConfigProperty(name = "archiv.ocr.container-image") Optional<String> containerImage,
            @ConfigProperty(name = "archiv.ocr.docker", defaultValue = "docker") String docker,
            @ConfigProperty(name = "archiv.ocr.pfad") String pfad) {
        return new OcrEinstellungen(zeitgrenze, jobs, arbeitsverzeichnis, containerImage, docker, pfad);
    }
}
