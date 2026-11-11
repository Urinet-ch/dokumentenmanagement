package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.Dateityp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Driven Port: erkennt den Dateityp nur am Inhalt (Magic Bytes, Req 3.3), nie an Name, Endung oder Content-Type;
 * Adapter {@code TikaTyperkennung}. Leer, wenn der Inhalt zu keinem Typ aus {@link Dateityp} passt.
 */
public interface Typerkennung {

    Optional<Dateityp> erkennen(Path datei);
}
