package ch.urinet.archiv.verarbeitung.domain;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Eine Quelle der Verarbeitung: Original oder, bei der Handy-Erfassung, Arbeitsfassung (Req 5.10). Bewusst ohne
 * Dateinamen: Der Adapter benennt seine Kopien selbst, damit kein Name eines Benutzers in eine Befehlszeile gelangt.
 */
public record OcrQuelle(Quelltyp typ, Inhalt inhalt) {

    public OcrQuelle {
        Objects.requireNonNull(typ, "typ");
        Objects.requireNonNull(inhalt, "inhalt");
    }

    /** Öffnet den Inhalt; der Adapter liest ihn genau einmal und schliesst ihn. */
    @FunctionalInterface
    public interface Inhalt {
        InputStream oeffnen() throws IOException;
    }
}
