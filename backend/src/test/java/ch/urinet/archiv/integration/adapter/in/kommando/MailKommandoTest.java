package ch.urinet.archiv.integration.adapter.in.kommando;

import ch.urinet.archiv.integration.application.mail.MailEinrichten;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Verschluesselung;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        assertThat(MailPasswortEingabe.vorabLesen(eingabe("s3hr-gehéim\r\nrest\n"))).isEmpty();
        assertThat(MailPasswortEingabe.nehmen()).containsExactly("s3hr-gehéim".toCharArray());
        assertThat(MailPasswortEingabe.nehmen()).as("danach leer").isEmpty();

        assertThat(MailPasswortEingabe.vorabLesen(eingabe(""))).contains(MailPasswortEingabe.FEHLT);
        assertThat(MailPasswortEingabe.vorabLesen(eingabe("\nrest\n"))).contains(MailPasswortEingabe.FEHLT);
        assertThat(MailPasswortEingabe.nehmen()).isEmpty();
        // Leerzeichen am Rand gehören zum Passwort
        assertThat(MailPasswortEingabe.vorabLesen(eingabe("  pw x  \n"))).isEmpty();
        assertThat(new String(MailPasswortEingabe.nehmen())).isEqualTo("  pw x  ");
    }

    @Test
    void zuLangeZeileIstFachlicherFehlerUndBleibtNichtImHalter() throws IOException {
        String genau = "x".repeat(MailPasswortEingabe.HOECHSTENS);
        assertThat(MailPasswortEingabe.vorabLesen(eingabe(genau + "\r\n"))).isEmpty();
        assertThat(new String(MailPasswortEingabe.nehmen())).isEqualTo(genau);
        // ohne Zeilenende wie eine versehentlich umgeleitete Datei
        assertThat(MailPasswortEingabe.vorabLesen(eingabe(genau))).isEmpty();
        assertThat(MailPasswortEingabe.nehmen()).hasSize(MailPasswortEingabe.HOECHSTENS);

        assertThat(MailPasswortEingabe.vorabLesen(eingabe(genau + "y\n"))).contains(MailPasswortEingabe.ZU_LANG);
        assertThat(MailPasswortEingabe.vorabLesen(eingabe("z".repeat(100_000)))).contains(MailPasswortEingabe.ZU_LANG);
        assertThat(MailPasswortEingabe.nehmen()).isEmpty();
    }

    @Test
    void leeresPasswortLehntDieEchtePruefungInMailEinrichtenAb() {
        MandantCode urinet = new MandantCode("urinet");
        MandantRegister register = new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return code.equals(urinet);
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return List.of(new Mandant(urinet, "UriNet", urinet.schema(), "b", Geschaeftsjahresende.STANDARD,
                        true));
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                return Optional.empty();
            }
        };
        Verschluesselung schluessel = new Verschluesselung() {
            @Override
            public boolean verfuegbar() {
                return true;
            }

            @Override
            public byte[] verschluesseln(byte[] klartext, String bezug) {
                throw new AssertionError("ohne Passwort wird nichts verschlüsselt");
            }

            @Override
            public byte[] entschluesseln(byte[] chiffrat, String bezug) {
                throw new UnsupportedOperationException();
            }
        };
        // Konfiguration null: ohne Passwort darf nichts gespeichert werden
        MailKommando kommando = new MailKommando(new MailEinrichten(register, null, schluessel));
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();

        assertThat(kommando.ausfuehren(List.of("einrichten", "urinet", "imap.example.invalid", "993",
                        "archiv@example.invalid", "a@example.invalid"), () -> new char[0],
                new PrintStream(fehler, true, StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains(MailPasswortEingabe.FEHLT);
    }

    private static ByteArrayInputStream eingabe(String inhalt) {
        return new ByteArrayInputStream(inhalt.getBytes(StandardCharsets.UTF_8));
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
