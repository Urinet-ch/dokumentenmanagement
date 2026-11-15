package ch.urinet.archiv.sicherheit.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WebseitenAbsenderPruefungTest {

    @TempDir
    Path verzeichnis;

    @Test
    void prueftIpv4Ipv6UndLaedtGeaenderteDateiNeu() throws Exception {
        Path datei = verzeichnis.resolve("absender.properties");
        Files.writeString(datei, "wordpress=203.0.113.0/24,2001:db8::/32\n");
        WebseitenAbsenderPruefung pruefung = new WebseitenAbsenderPruefung(datei);
        pruefung.initialisieren();

        assertThat(pruefung.erlaubt("wordpress", Optional.of("203.0.113.91"))).isTrue();
        assertThat(pruefung.erlaubt("wordpress", Optional.of("203.0.114.1"))).isFalse();
        assertThat(pruefung.erlaubt("wordpress", Optional.of("2001:db8::12"))).isTrue();
        assertThat(pruefung.erlaubt("unbekannt", Optional.of("203.0.113.91"))).isFalse();
        assertThat(pruefung.erlaubt("wordpress", Optional.empty())).isFalse();

        Files.writeString(datei, "wordpress=198.51.100.0/24\n");
        Files.setLastModifiedTime(datei, FileTime.from(Instant.now().plusSeconds(2)));
        assertThat(pruefung.erlaubt("wordpress", Optional.of("198.51.100.8"))).isTrue();
        assertThat(pruefung.erlaubt("wordpress", Optional.of("203.0.113.91"))).isFalse();
    }

    @Test
    void fehlendeOderUngueltigeKonfigurationSchliesstAb() throws Exception {
        Path datei = verzeichnis.resolve("fehlt.properties");
        WebseitenAbsenderPruefung pruefung = new WebseitenAbsenderPruefung(datei);
        pruefung.initialisieren();
        assertThat(pruefung.erlaubt("wordpress", Optional.of("203.0.113.1"))).isFalse();

        Files.writeString(datei, "wordpress=hostname.example\n");
        assertThat(pruefung.erlaubt("wordpress", Optional.of("203.0.113.1"))).isFalse();
    }
}
