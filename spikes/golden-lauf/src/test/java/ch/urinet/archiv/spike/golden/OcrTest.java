package ch.urinet.archiv.spike.golden;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OCR-Cache: nur vollständige Läufe zählen; ein abgebrochener Lauf hinterlässt nichts. Docker per Skript ersetzt. */
class OcrTest {

    @TempDir
    Path tmp;

    /** Ersatz für «docker run …» und «docker kill <name>»: findet den Arbeitsordner im Argument «-v <ordner>:/data». */
    private Path skript(String rumpf) throws IOException {
        Path s = tmp.resolve("docker-ersatz.sh");
        Files.writeString(s, """
                #!/bin/sh
                if [ "$1" = kill ]; then echo "$2" >> "${0%/*}/getoetet.txt"; exit 0; fi
                for a in "$@"; do case "$a" in *:/data) d="${a%:/data}";; esac; done
                """ + rumpf + "\n");
        Files.setPosixFilePermissions(s, PosixFilePermissions.fromString("rwx------"));
        return s;
    }

    private Path beleg() throws IOException {
        Path b = tmp.resolve("beleg.pdf");
        Files.writeString(b, "%PDF synthetisch");
        return b;
    }

    @Test
    void abbruchNachTextOhneSeitenbilderHinterlaesstKeinenCache() throws Exception {
        Path arbeit = tmp.resolve("cache/abc");
        Ocr ocr = new Ocr("bild", "deu", Duration.ofSeconds(20),
                skript("echo teiltext > \"$d/text.txt\"; exit 3").toString());
        assertThatThrownBy(() -> ocr.verarbeite(beleg(), arbeit)).isInstanceOf(IOException.class).hasMessageContaining("Exit 3");
        assertThat(arbeit).doesNotExist();
        assertThat(Ocr.ausCache(arbeit)).isEmpty();
    }

    @Test
    void textOhneFertigMarkeGiltNichtAlsCache() throws Exception {
        Path arbeit = tmp.resolve("cache/alt");
        Files.createDirectories(arbeit);
        Files.writeString(arbeit.resolve("text.txt"), "halbfertig");
        assertThat(Ocr.ausCache(arbeit)).isEmpty();
    }

    @Test
    void erfolgreicherLaufSchreibtFertigUndWirdGelesen() throws Exception {
        Path arbeit = tmp.resolve("cache/ok");
        Files.createDirectories(arbeit);
        Files.writeString(arbeit.resolve("text.txt"), "veraltet");
        Ocr ocr = new Ocr("bild", "deu", Duration.ofSeconds(20),
                skript("echo neu > \"$d/text.txt\"; printf x > \"$d/seite-1.png\"; printf x > \"$d/seite-2.png\"").toString());
        Ocr.Resultat r = ocr.verarbeite(beleg(), arbeit);
        assertThat(r.text()).isEqualTo("neu\n");
        assertThat(r.seiten()).extracting(p -> p.getFileName().toString()).containsExactly("seite-1.png", "seite-2.png");
        assertThat(arbeit.resolve(Ocr.FERTIG)).exists();
        assertThat(Ocr.ausCache(arbeit)).get().extracting(Ocr.Resultat::text).isEqualTo("neu\n");
    }

    @Test
    void ohneSeitenbilderTrotzExitNullKeinCache() throws Exception {
        Path arbeit = tmp.resolve("cache/leer");
        Ocr ocr = new Ocr("bild", "deu", Duration.ofSeconds(20), skript("echo text > \"$d/text.txt\"").toString());
        assertThatThrownBy(() -> ocr.verarbeite(beleg(), arbeit)).hasMessageContaining("Seitenbilder");
        assertThat(arbeit).doesNotExist();
    }

    @Test
    void zeitgrenzeStopptContainerUndLoeschtArbeitsordner() throws Exception {
        Path arbeit = tmp.resolve("cache/langsam");
        Ocr ocr = new Ocr("bild", "deu", Duration.ofMillis(300),
                skript("echo teil > \"$d/text.txt\"; sleep 5").toString());
        assertThatThrownBy(() -> ocr.verarbeite(beleg(), arbeit)).hasMessageContaining("Zeitgrenze");
        assertThat(arbeit).doesNotExist();
        assertThat(Files.readString(tmp.resolve("getoetet.txt"))).startsWith("golden-ocr-");
    }

    @Test
    void ueberzaehligeSeiteAusAltemEintragOhneFertigVerschwindet() throws Exception {
        Path arbeit = tmp.resolve("cache/alt-mit-seiten");
        Files.createDirectories(arbeit);
        Files.writeString(arbeit.resolve("text.txt"), "alt");
        Files.writeString(arbeit.resolve("seite-9.png"), "alt");
        Ocr ocr = new Ocr("bild", "deu", Duration.ofSeconds(20),
                skript("echo neu > \"$d/text.txt\"; printf x > \"$d/seite-1.png\"").toString());
        Ocr.Resultat r = ocr.verarbeite(beleg(), arbeit);
        assertThat(r.seiten()).extracting(p -> p.getFileName().toString()).containsExactly("seite-1.png");
        assertThat(arbeit.resolve("seite-9.png")).doesNotExist();
    }

    @Test
    void loeschfehlerBrichtNichtAbSondernBleibtFehlschlag() throws Exception {
        Path arbeit = tmp.resolve("cache/gesperrt");
        Ocr ocr = new Ocr("bild", "deu", Duration.ofSeconds(20),
                skript("echo teil > \"$d/text.txt\"; chmod 500 \"$d\"; exit 3").toString());
        try {
            assertThatThrownBy(() -> ocr.verarbeite(beleg(), arbeit))
                    .isInstanceOf(Ocr.OcrFehler.class).hasMessageContaining("Exit 3")
                    .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());
            assertThat(Ocr.ausCache(arbeit)).isEmpty();
        } finally {
            Files.setPosixFilePermissions(arbeit, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void fehlertextOhnePfade() {
        assertThat(Ocr.grund(new NoSuchFileException("/Users/x/golden/belege/Original Lieferant 2026.pdf")))
                .isEqualTo("NoSuchFileException");
        assertThat(Ocr.grund(new AccessDeniedException("/Users/x/golden/belege/Lohn.pdf"))).isEqualTo("AccessDeniedException");
        assertThat(Ocr.grund(new Ocr.OcrFehler("docker Exit 3"))).isEqualTo("docker Exit 3");
    }
}
