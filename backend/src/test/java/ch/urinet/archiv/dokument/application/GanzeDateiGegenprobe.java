package ch.urinet.archiv.dokument.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gegenprobe zu {@code ArchitekturTest.dateienNieGanzImHeap}: liest eine Datei ganz in den Heap. */
public final class GanzeDateiGegenprobe {

    byte[] lesen(Path datei) throws IOException {
        return Files.readAllBytes(datei);
    }
}
