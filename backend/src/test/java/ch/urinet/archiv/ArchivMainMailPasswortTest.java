package ch.urinet.archiv;

import ch.urinet.archiv.integration.adapter.in.kommando.MailPasswortEingabe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vorab-Lesen des Passworts für {@code mail einrichten} in {@code ArchivMain.main}, ohne Start der Anwendung: Exit-Codes
 * und Meldungen auf stderr. Den Weg über den echten Einstieg prüft {@code MailKommandoStartTest}.
 */
class ArchivMainMailPasswortTest {

    private final ByteArrayOutputStream fehler = new ByteArrayOutputStream();
    private PrintStream vorher;

    @BeforeEach
    void stderrMitschneiden() {
        vorher = System.err;
        System.setErr(new PrintStream(fehler, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stderrZuruecksetzen() {
        System.setErr(vorher);
        MailPasswortEingabe.nehmen();
    }

    @Test
    void nichtLesbareStandardeingabeIstExit3OhnePasswortInDerAusgabe() {
        // liefert einen Teil des Passworts und scheitert dann mit einer Meldung, die es enthält
        InputStream kaputt = new InputStream() {
            private final InputStream anfang = new ByteArrayInputStream(
                    "s3hr-geheim".getBytes(StandardCharsets.UTF_8));

            @Override
            public int read() throws IOException {
                int b = anfang.read();
                if (b == -1) {
                    throw new IOException("s3hr-geheim");
                }
                return b;
            }
        };

        assertThat(ArchivMain.mailPasswortVorabLesen(kaputt)).isEqualTo(ArchivMain.TECHNISCHER_FEHLER);
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains("Technischer Fehler", "IOException")
                .doesNotContain("s3hr");
        assertThat(MailPasswortEingabe.nehmen()).isEmpty();
    }

    @Test
    void fehlendesOderZuLangesPasswortIstExit1MitFesterMeldung() {
        assertThat(ArchivMain.mailPasswortVorabLesen(new ByteArrayInputStream(new byte[0])))
                .isEqualTo(ArchivMain.FACHLICHER_FEHLER);
        byte[] lang = "x".repeat(MailPasswortEingabe.HOECHSTENS + 1).getBytes(StandardCharsets.UTF_8);
        assertThat(ArchivMain.mailPasswortVorabLesen(new ByteArrayInputStream(lang)))
                .isEqualTo(ArchivMain.FACHLICHER_FEHLER);

        assertThat(fehler.toString(StandardCharsets.UTF_8))
                .contains(MailPasswortEingabe.FEHLT, MailPasswortEingabe.ZU_LANG).doesNotContain("xxx");
    }

    @Test
    void passwortIstExit0OhneAusgabe() {
        assertThat(ArchivMain.mailPasswortVorabLesen(new ByteArrayInputStream(
                "s3hr-geheim\n".getBytes(StandardCharsets.UTF_8)))).isZero();
        assertThat(fehler.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(new String(MailPasswortEingabe.nehmen())).isEqualTo("s3hr-geheim");
    }
}
