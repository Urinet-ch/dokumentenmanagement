package ch.urinet.archiv.integration.adapter.in.kommando;

import ch.urinet.archiv.integration.application.mail.MailEinrichten;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aufruf von {@code archiv mail …} (Spec 02, Aufgabe 6.2): Passwort nur über stdin, nie in Ausgabe oder Fehlertext. Den
 * Weg über {@code ArchivMain.main} (Lesen vor dem Start) prüft {@code MailKommandoStartTest}.
 */
class MailKommandoTest {

    @Test
    void liestEinrichtenUndAbschaltenUndLehntFalscheAufrufeAb() {
        assertThat(MailKommando.lesen(List.of("einrichten", "urinet", "imap.example.invalid", "993",
                "archiv@example.invalid", "a@example.invalid", "b@example.invalid")))
                .hasValueSatisfying(a -> {
                    assertThat(a.port()).isEqualTo(993);
                    assertThat(a.absender()).containsExactly("a@example.invalid", "b@example.invalid");
                });
        assertThat(MailKommando.lesen(List.of("abschalten", "urinet"))).isPresent();
        assertThat(MailKommando.lesen(List.of("einrichten", "urinet", "host", "993", "benutzer"))).isEmpty();
        assertThat(MailKommando.lesen(List.of("einrichten", "urinet", "host", "imaps", "benutzer", "a@b")))
                .isEmpty();
        assertThat(MailKommando.lesen(List.of("loeschen", "urinet"))).isEmpty();
    }

    @Test
    void dasPasswortKommtAusDerErstenZeileDerStandardeingabeUndErscheintNie() {
        List<String> passwoerter = new ArrayList<>();
        MailKommando kommando = new MailKommando(new MailEinrichten(null, null, null) {
            @Override
            public void einrichten(String code, String host, int port, String benutzer, String passwort,
                                   List<String> absender) {
                passwoerter.add(passwort);
            }
        });
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        char[] zeichen = "s3hr-geheim".toCharArray();
        int code = kommando.ausfuehren(List.of("einrichten", "urinet", "imap.example.invalid", "993",
                        "archiv@example.invalid", "a@example.invalid"),
                () -> zeichen,
                new PrintStream(fehler, true, StandardCharsets.UTF_8));
        assertThat(code).isZero();
        assertThat(passwoerter).containsExactly("s3hr-geheim");
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains("eingerichtet").doesNotContain("s3hr-geheim");
        assertThat(zeichen).as("nach dem Einrichten überschrieben").containsOnly('\0');
    }

    @Test
    void halterLiestNurDieErsteZeileUndGibtSieGenauEinmalHeraus() throws IOException {
        assertThat(MailPasswortEingabe.vorabLesen(new ByteArrayInputStream(
                "s3hr-gehéim\r\nrest\n".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(MailPasswortEingabe.nehmen()).containsExactly("s3hr-gehéim".toCharArray());
        assertThat(MailPasswortEingabe.nehmen()).as("danach leer").isEmpty();

        assertThat(MailPasswortEingabe.vorabLesen(new ByteArrayInputStream(new byte[0]))).isFalse();
        assertThat(MailPasswortEingabe.vorabLesen(new ByteArrayInputStream("\nrest\n".getBytes(StandardCharsets.UTF_8))))
                .isFalse();
        assertThat(MailPasswortEingabe.nehmen()).isEmpty();
        // länger als der Anfangspuffer, ohne Zeilenende
        String lang = "x".repeat(200);
        assertThat(MailPasswortEingabe.vorabLesen(new ByteArrayInputStream(lang.getBytes(StandardCharsets.UTF_8))))
                .isTrue();
        assertThat(new String(MailPasswortEingabe.nehmen())).isEqualTo(lang);
    }

    @Test
    void leeresPasswortIstFachlicherFehler() {
        MailKommando kommando = new MailKommando(new MailEinrichten(null, null, null) {
            @Override
            public void einrichten(String code, String host, int port, String benutzer, String passwort,
                                   List<String> absender) {
                if (passwort.isEmpty()) {
                    throw new Fehler(MailPasswortEingabe.FEHLT);
                }
            }
        });
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        assertThat(kommando.ausfuehren(List.of("einrichten", "urinet", "h", "993", "b", "a@example.invalid"),
                () -> new char[0], new PrintStream(fehler, true, StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains("Passwort fehlt");
    }

    @Test
    void fachlicherFehlerEndetMitExitCode1() {
        MailKommando kommando = new MailKommando(new MailEinrichten(null, null, null) {
            @Override
            public void einrichten(String code, String host, int port, String benutzer, String passwort,
                                   List<String> absender) {
                throw new Fehler("Mandant urinet ist nicht aktiv");
            }
        });
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        assertThat(kommando.ausfuehren(List.of("einrichten", "urinet", "h", "993", "b", "a@example.invalid"),
                "pw"::toCharArray,
                new PrintStream(fehler, true, StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(kommando.ausfuehren(List.of("einrichten"), () -> new char[0],
                new PrintStream(fehler, true, StandardCharsets.UTF_8))).isEqualTo(2);
    }
}
