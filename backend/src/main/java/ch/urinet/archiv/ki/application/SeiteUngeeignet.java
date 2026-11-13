package ch.urinet.archiv.ki.application;

import java.io.IOException;

/**
 * Eine gerenderte Seite hat einen ungültigen Kopf oder mehr Pixel als {@code archiv.ki.render-pixel-max} (Review PR
 * #120). Eine Eigenschaft der Seite, kein vorübergehender Fehler: Ein Fehlversuch brächte bei jedem Versuch dasselbe.
 * Der Bildweg geht darum ohne Seitenbild weiter, der QR-Weg lässt die Seite aus (design.md «Speicher»).
 */
public class SeiteUngeeignet extends IOException {

    public SeiteUngeeignet(String meldung) {
        super(meldung);
    }
}
