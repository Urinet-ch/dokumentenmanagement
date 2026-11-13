package ch.urinet.archiv.ki.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Driven Port für das Seitenbild des Bildwegs (Req 7.1, design.md «KI»): alle Seiten untereinander, längste Kante
 * höchstens 2000 Pixel, als JPEG, wie im Golden-Lauf ({@code Seitenbild}).
 */
public interface SeitenbildPort {

    byte[] jpeg(List<Path> seiten) throws IOException;
}
