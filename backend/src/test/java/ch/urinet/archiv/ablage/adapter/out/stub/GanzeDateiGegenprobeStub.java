package ch.urinet.archiv.ablage.adapter.out.stub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gegenprobe zu {@code ArchitekturTest.dateienNieGanzImHeap}: im Stub erlaubt. */
public final class GanzeDateiGegenprobeStub {

    byte[] lesen(Path datei) throws IOException {
        return Files.readAllBytes(datei);
    }
}
