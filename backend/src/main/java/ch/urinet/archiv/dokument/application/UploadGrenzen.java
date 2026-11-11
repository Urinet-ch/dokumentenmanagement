package ch.urinet.archiv.dokument.application;

/** Grenzen des Uploads aus der Konfiguration (design.md «Upload», Req 3.7). */
public interface UploadGrenzen {

    /** Höchste Grösse einer einzelnen Datei in Bytes ({@code archiv.upload.max-datei}). */
    long maxDatei();
}
