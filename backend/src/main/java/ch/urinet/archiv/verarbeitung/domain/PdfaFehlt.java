package ch.urinet.archiv.verarbeitung.domain;

import java.util.Objects;

/**
 * Keine PDF/A-Fassung möglich (Req 5.9): Das Dokument geht mit dem Vermerk {@code pdfa_fehlt} und diesem Fehlertext in
 * den Eingangskorb; der Inhaber entscheidet, ob das Original allein archiviert oder verworfen wird. Der Fehlertext ist
 * fest und enthält keine Ausgabe der Werkzeuge (Req 16.7).
 */
public record PdfaFehlt(Grund grund) {

    public enum Grund {
        /** Das PDF ist verschlüsselt (Passwort oder Rechte); OCRmyPDF verarbeitet es nicht. */
        VERSCHLUESSELT("Das PDF ist verschlüsselt. Es liess sich keine PDF/A-Fassung erzeugen. Original allein "
                + "archivieren oder verwerfen."),
        /** OCRmyPDF hat ein PDF erzeugt, das nicht als PDF/A gilt (Ghostscript-Wandlung gescheitert). */
        KONVERTIERUNG("Die Wandlung in PDF/A ist gescheitert. Original allein archivieren oder verwerfen.");

        private final String fehlertext;

        Grund(String fehlertext) {
            this.fehlertext = fehlertext;
        }
    }

    public PdfaFehlt {
        Objects.requireNonNull(grund, "grund");
    }

    public String fehlertext() {
        return grund.fehlertext;
    }
}
