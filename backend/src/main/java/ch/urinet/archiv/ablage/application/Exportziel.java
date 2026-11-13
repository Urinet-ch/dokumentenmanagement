package ch.urinet.archiv.ablage.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Stromweises Schreiben des ZIP eines Jahresexports nach {@code exporte/<id>.zip} (Spec 02, Req 7.1): Der S3-Adapter
 * lädt in Teilen fester Grösse hoch (Multipart-Upload), hält also höchstens einen Teil im Speicher, nie die ganze
 * Datei. {@link #strom()} schliesst der Aufrufer nicht; {@link #abschliessen} legt die Datei ab, {@link #close} ohne
 * Abschluss bricht ab und hinterlässt nichts (unvollständige Uploads entfernt sonst der Lebenszyklus).
 */
public interface Exportziel extends AutoCloseable {

    OutputStream strom();

    /** Schliesst den Upload ab: Schlüssel, Versions-ID, SHA-256 und Grösse der geschriebenen Bytes, ohne Sperre. */
    AbgelegteDatei abschliessen() throws IOException;

    @Override
    void close();
}
