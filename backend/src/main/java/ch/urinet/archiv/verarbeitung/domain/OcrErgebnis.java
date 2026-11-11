package ch.urinet.archiv.verarbeitung.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ergebnis der Verarbeitung (Req 5.1–5.3, 5.8, 5.9). Die Pfade liegen im Arbeitsverzeichnis des Laufs und gelten nur
 * während der Verwertung; danach löscht der Adapter das Verzeichnis.
 *
 * @param pdfa              PDF/A-2b-Fassung; fehlt genau dann, wenn {@code pdfaFehlt} gesetzt ist
 * @param volltext          Text aus {@code pdftotext}, Seiten getrennt durch {@code \f}; leer, wenn keiner lesbar ist
 * @param vorschaubilder    PNG je Seite in Seitenreihenfolge (72 dpi)
 * @param ocrAusgefuehrt    {@code false}, wenn das PDF schon eine Textebene hatte ({@code --skip-text}, Req 5.2)
 * @param signaturUngueltig die PDF/A-Fassung hat die digitale Signatur des Originals verloren (Req 5.8)
 */
public record OcrErgebnis(Optional<Path> pdfa, Optional<PdfaFehlt> pdfaFehlt, String volltext,
                          List<Path> vorschaubilder, boolean ocrAusgefuehrt, boolean signaturUngueltig) {

    public OcrErgebnis {
        Objects.requireNonNull(pdfa, "pdfa");
        Objects.requireNonNull(pdfaFehlt, "pdfaFehlt");
        Objects.requireNonNull(volltext, "volltext");
        vorschaubilder = List.copyOf(vorschaubilder);
        if (pdfa.isPresent() == pdfaFehlt.isPresent()) {
            throw new IllegalArgumentException("Entweder PDF/A oder pdfa_fehlt");
        }
    }
}
