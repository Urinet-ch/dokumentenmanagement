package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Szenarien des Scan-Eingangs übersetzen die Quellen aus {@code deploy/infomaniak/sftp} im Backend (Aufgabe 27.3);
 * beide Maven-Projekte müssen darum dieselbe Version von Apache MINA SSHD nennen. Dependabot aktualisiert sie getrennt,
 * dieser Test bricht ab, bis der zweite PR nachzieht.
 */
class ScanEingangVersionTest {

    private static final Pattern VERSION = Pattern.compile("<sshd\\.version>([^<]+)</sshd\\.version>");

    @Test
    void backendUndDienstSftpNutzenDieselbeVersion() throws IOException {
        assertThat(version("backend/pom.xml")).isEqualTo(version("deploy/infomaniak/sftp/pom.xml"));
    }

    private static String version(String pom) throws IOException {
        Matcher m = VERSION.matcher(Files.readString(GemeinsamSchritte.REPO.resolve(pom), StandardCharsets.UTF_8));
        assertThat(m.find()).as(pom).isTrue();
        return m.group(1);
    }
}
