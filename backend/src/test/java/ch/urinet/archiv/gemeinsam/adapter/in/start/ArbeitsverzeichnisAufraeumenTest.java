package ch.urinet.archiv.gemeinsam.adapter.in.start;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArbeitsverzeichnisAufraeumenTest {

    private static final Instant JETZT = Instant.now();
    private static final Instant GRENZE = JETZT.minus(Duration.ofHours(1));
    private static final FileTime ALT = FileTime.from(JETZT.minus(Duration.ofHours(2)));
    private static final FileTime JUNG = FileTime.from(JETZT.minus(Duration.ofMinutes(10)));

    @TempDir
    Path wurzel;

    @Test
    void loeschtNurResteAelterAlsDieGrenzeUndBehaeltDieOberstenOrdner() throws IOException {
        Path upload = Files.createDirectories(wurzel.resolve("upload"));
        Path altUpload = datei(upload.resolve("alt"), ALT);
        Path jungUpload = datei(upload.resolve("jung"), JUNG);
        Path altLauf = Files.createDirectories(wurzel.resolve("ocr/lauf-alt"));
        datei(altLauf.resolve("quelle-1.pdf"), ALT);
        Files.setLastModifiedTime(altLauf, ALT);
        Path jungLauf = Files.createDirectories(wurzel.resolve("ocr/lauf-jung"));
        Path jungInJung = datei(jungLauf.resolve("pdfa.pdf"), JUNG);
        Path gemischt = Files.createDirectories(wurzel.resolve("ocr/lauf-gemischt"));
        Path jungInAlt = datei(gemischt.resolve("ausgabe.log"), JUNG);
        datei(gemischt.resolve("quelle-1.pdf"), ALT);
        Files.setLastModifiedTime(gemischt, ALT);
        Files.setLastModifiedTime(upload, ALT);
        Files.setLastModifiedTime(wurzel.resolve("ocr"), ALT);

        var ergebnis = ArbeitsverzeichnisAufraeumen.aufraeumen(wurzel, GRENZE);

        assertThat(altUpload).doesNotExist();
        assertThat(altLauf).doesNotExist();
        assertThat(gemischt.resolve("quelle-1.pdf")).doesNotExist();
        assertThat(jungUpload).exists();
        assertThat(jungInJung).exists();
        assertThat(jungInAlt).exists();
        assertThat(upload).isDirectory();
        assertThat(wurzel.resolve("ocr")).isDirectory();
        assertThat(ergebnis).isEqualTo(new ArbeitsverzeichnisAufraeumen.Ergebnis(4, 0));
    }

    @Test
    void legtDasVerzeichnisAnUndBrichtAbWennEsNichtAnlegbarIst() throws IOException {
        Path neu = wurzel.resolve("a/b/arbeit");
        ArbeitsverzeichnisAufraeumen.bereitstellen(neu);
        assertThat(neu).isDirectory().isEmptyDirectory();

        Path keinOrdner = datei(wurzel.resolve("datei"), JUNG);
        assertThatThrownBy(() -> ArbeitsverzeichnisAufraeumen.bereitstellen(keinOrdner.resolve("arbeit")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv.arbeit.verzeichnis");
    }

    private static Path datei(Path pfad, FileTime zeit) throws IOException {
        Files.writeString(pfad, "x");
        Files.setLastModifiedTime(pfad, zeit);
        return pfad;
    }
}
