package ch.urinet.archiv.dokument.adapter.out.tika;

import ch.urinet.archiv.dokument.application.Typerkennung;
import ch.urinet.archiv.dokument.domain.Dateityp;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.tika.Tika;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Erkennt den Typ nur am Inhalt (Magic Bytes, Req 3.3): Apache Tika bekommt den Strom ohne Namen, damit weder die
 * Endung noch der Content-Type der Anfrage zählen.
 */
@ApplicationScoped
public class TikaTyperkennung implements Typerkennung {

    private final Tika tika = new Tika();

    @Override
    public Optional<Dateityp> erkennen(Path datei) {
        try (InputStream ein = new BufferedInputStream(Files.newInputStream(datei))) {
            return Dateityp.ausMime(tika.detect(ein));
        } catch (IOException e) {
            throw new UncheckedIOException("Upload-Datei nicht lesbar", e);
        }
    }
}
