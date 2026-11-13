package ch.urinet.archiv.integration.application.mail;

import java.nio.file.Path;
import java.util.List;

/** Driven Port: PDF aus dem Text einer Mail ohne Anhang (Spec 02, Req 1.3; tasks.md 6.1: PDFBox). */
public interface TextPdfPort {

    /** Schreibt ein PDF mit den Kopfzeilen und dem Text nach {@code ziel}; nicht darstellbare Zeichen werden «?». */
    void erzeugen(List<String> kopf, String text, Path ziel);
}
