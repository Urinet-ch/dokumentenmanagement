package ch.urinet.archiv.verarbeitung.adapter.out.stub;

import ch.urinet.archiv.verarbeitung.application.OcrPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/** Dev-Stub von {@link OcrPort}: ohne OCRmyPDF; aktiv mit {@code archiv.ocr.stub=true} (Profil dev). */
@ApplicationScoped
@Typed(OcrStub.class)
public class OcrStub implements OcrPort {
}
