package ch.urinet.archiv.verarbeitung.adapter.out;

import ch.urinet.archiv.verarbeitung.adapter.out.stub.OcrStub;
import ch.urinet.archiv.verarbeitung.application.OcrPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
}
