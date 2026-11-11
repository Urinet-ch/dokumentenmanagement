package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.VeraPDFFoundry;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prüft eine Datei mit veraPDF gegen PDF/A-2b (Req 5.1). */
public final class PdfaPruefung {

    static {
        VeraGreenfieldFoundryProvider.initialise();
    }

    private PdfaPruefung() {
    }

    public static boolean istPdfa2b(Path pdf) throws Exception {
        try (VeraPDFFoundry foundry = Foundries.defaultInstance();
             InputStream ein = Files.newInputStream(pdf);
             PDFAParser parser = foundry.createParser(ein, PDFAFlavour.PDFA_2_B);
             PDFAValidator pruefer = foundry.createValidator(PDFAFlavour.PDFA_2_B, false)) {
            return pruefer.validate(parser).isCompliant();
        }
    }
}
