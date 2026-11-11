package ch.urinet.archiv.verarbeitung.adapter.out;

import ch.urinet.archiv.verarbeitung.adapter.out.stub.OcrStub;
import ch.urinet.archiv.verarbeitung.application.OcrPort;
import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und echtem Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class OcrAuswahl {

    @Produces
    @ApplicationScoped
    public OcrPort ocr(@ConfigProperty(name = "archiv.ocr.stub") boolean stub, OcrStub dev) {
        if (stub) {
            return dev;
        }
        throw new IllegalStateException("Kein Adapter für OcrPort vor Aufgabe 2.6; archiv.ocr.stub=true setzen");
    }

    /** Für die Startprüfung der Port-Auswahl: Der Adapter folgt mit 2.6. */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ocr.stub") boolean stub) {
        return new PortAuswahl("OcrPort", stub, false, Optional.of("2.6"));
    }
}
