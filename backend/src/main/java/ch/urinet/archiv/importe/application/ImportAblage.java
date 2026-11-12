package ch.urinet.archiv.importe.application;

import java.nio.file.Path;
import java.util.UUID;

/** Legt die Originaldatei eines Import-Laufs unter {@code importe/<laufId>.csv} im Bucket des Mandanten ab (Req 13.8). */
@FunctionalInterface
public interface ImportAblage {

    /** Legt die Datei ab und gibt ihre SHA-256 zurück, wie sie die Ablage beim Hochladen berechnet hat. */
    String ablegen(UUID laufId, Path datei);
}
