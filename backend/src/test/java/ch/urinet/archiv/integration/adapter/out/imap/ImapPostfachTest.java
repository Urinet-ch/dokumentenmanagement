package ch.urinet.archiv.integration.adapter.out.imap;

import ch.urinet.archiv.integration.TestPostfach;
import ch.urinet.archiv.integration.application.mail.PostfachNichtErreichbar;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Abruf;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Ziel;
import ch.urinet.archiv.integration.domain.mail.Postfach;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import jakarta.mail.Session;
import jakarta.mail.Flags;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImapPostfach} gegen GreenMail ohne Quarkus (Spec 02, Req 1.1, 1.5): Eine Nachricht, die immer scheitert,
 * blockiert das Fenster {@code max} nicht, sie wird zurückgestellt und nach der Wartezeit wieder versucht.
 */
class ImapPostfachTest {

    @TempDir
    Path verzeichnis;

    @Test
    void gmailLabelBleibtUngelesenUndWirdWederVerschobenNochAbonniert() throws Exception {
        TestPostfach test = TestPostfach.neu();
        String label = "Gmail";
        test.ordnerAnlegen(label);
        String id = zustellen(test, label, "Gmail-Nachricht");
        Set<Flags.Flag> vorher = test.flags(label, id);
        assertThat(vorher).doesNotContain(Flags.Flag.SEEN);
        assertThat(test.ordnerAbonniert(label)).isFalse();
        ImapPostfach postfach = postfach();
        Postfach zugang = new Postfach(TestPostfach.HOST, TestPostfach.port(), test.login());
        List<String> gelesen = new ArrayList<>();

        assertThat(postfach.abrufenNurLesend(zugang, test.passwort(), label, LocalDate.of(2020, 1, 1), 10,
                n -> {
                    gelesen.add(n.messageId());
                    return Ziel.ARCHIVIERT;
                })).isEqualTo(new Abruf(1, 0));
        assertThat(postfach.abrufenNurLesend(zugang, test.passwort(), label, LocalDate.of(2020, 1, 1), 10,
                n -> {
                    gelesen.add(n.messageId());
                    return Ziel.ARCHIVIERT;
                })).isEqualTo(new Abruf(1, 0));

        assertThat(gelesen).containsExactly(id, id);
        assertThat(test.ordner(label)).containsExactly(id);
        assertThat(test.flags(label, id)).isEqualTo(vorher);
        assertThat(test.ordnerAbonniert(label)).isFalse();
    }

    @Test
    void falschesGmailPasswortWirdAlsAnmeldefehlerOhnePasswortWeitergereicht() throws Exception {
        TestPostfach test = TestPostfach.neu();
        Postfach zugang = new Postfach(TestPostfach.HOST, TestPostfach.port(), test.login());
        String falsch = "synthetisches-falsches-passwort";

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThatThrownBy(() -> postfach().abrufenNurLesend(zugang, falsch, "INBOX",
                    LocalDate.of(2020, 1, 1), 10, n -> Ziel.ARCHIVIERT))
                    .isInstanceOf(PostfachNichtErreichbar.class)
                    .hasMessage("Anmeldung")
                    .hasMessageNotContaining(falsch);
            assertThat(log.volltext()).doesNotContain(falsch);
        }
    }

    @Test
    void gmailAbrufBeachtetStartdatumUndMaxJeLauf() throws Exception {
        TestPostfach test = TestPostfach.neu();
        String label = "Gmail";
        test.ordnerAnlegen(label);
        String erste = zustellen(test, label, "Gmail-Nachricht 1");
        zustellen(test, label, "Gmail-Nachricht 2");
        Postfach zugang = new Postfach(TestPostfach.HOST, TestPostfach.port(), test.login());
        List<String> gelesen = new ArrayList<>();

        assertThat(postfach().abrufenNurLesend(zugang, test.passwort(), label, LocalDate.of(2020, 1, 1), 1,
                nachricht -> {
                    gelesen.add(nachricht.messageId());
                    return Ziel.ARCHIVIERT;
                })).isEqualTo(new Abruf(1, 0));
        assertThat(gelesen).hasSize(1);
        assertThat(gelesen).contains(erste);

        assertThat(postfach().abrufenNurLesend(zugang, test.passwort(), label,
                LocalDate.now(ZoneOffset.UTC).plusDays(1), 10, n -> Ziel.ARCHIVIERT))
                .isEqualTo(new Abruf(0, 0));
    }

    @Test
    void eineNachrichtDieScheitertWirdZurueckgestelltUndBlockiertDieUebrigenNicht() throws Exception {
        TestPostfach test = TestPostfach.neu();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(zustellen(test, "Nachricht " + i));
        }
        MutableUhr uhr = new MutableUhr(Instant.parse("2026-11-13T10:00:00Z"));
        ImapPostfach postfach = new ImapPostfach(Duration.ofSeconds(10), "Archiviert", "Quarantaene",
                Optional.of(Path.of(System.getProperty("user.dir"), "target", "greenmail", "ca.pem")), verzeichnis,
                60_000_000L, Duration.ofHours(1), 100 * 1024, uhr);
        Postfach zugang = new Postfach(TestPostfach.HOST, TestPostfach.port(), test.login());
        List<String> gesehen = new ArrayList<>();
        Abruf erster = postfach.abrufen(zugang, test.passwort(), 1, n -> {
            gesehen.add(n.messageId());
            if (n.messageId().equals(ids.getFirst())) {
                throw new IllegalStateException("immer kaputt");
            }
            return Ziel.ARCHIVIERT;
        });
        assertThat(erster).isEqualTo(new Abruf(0, 1));
        // der zweite Abruf überspringt die gescheiterte Nachricht und nimmt die nächste
        Abruf zweiter = postfach.abrufen(zugang, test.passwort(), 1, n -> {
            gesehen.add(n.messageId());
            return Ziel.ARCHIVIERT;
        });
        assertThat(zweiter).isEqualTo(new Abruf(1, 0));
        assertThat(gesehen).containsExactly(ids.get(0), ids.get(1));
        assertThat(test.ordner("Archiviert")).containsExactly(ids.get(1));
        // nach der Wartezeit kommt die gescheiterte Nachricht wieder zuerst
        uhr.jetzt = uhr.jetzt.plus(Duration.ofHours(1));
        postfach.abrufen(zugang, test.passwort(), 1, n -> {
            gesehen.add(n.messageId());
            return Ziel.ARCHIVIERT;
        });
        assertThat(gesehen.getLast()).isEqualTo(ids.getFirst());
        assertThat(test.ordner("INBOX")).containsExactly(ids.get(2));
    }

    private static String zustellen(TestPostfach test, String betreff) throws Exception {
        return zustellen(test, "INBOX", betreff);
    }

    private static String zustellen(TestPostfach test, String ordner, String betreff) throws Exception {
        MimeMessage nachricht = new MimeMessage(Session.getInstance(new Properties()));
        nachricht.setFrom("inhaber@example.invalid");
        nachricht.setSubject(betreff);
        nachricht.setText("Synthetischer Text");
        nachricht.saveChanges();
        String id = "<" + UUID.randomUUID() + "@test.example.invalid>";
        nachricht.setHeader("Message-ID", id);
        if ("INBOX".equals(ordner)) {
            test.zustellen(nachricht);
        } else {
            // GreenMail delivers to INBOX, then copies synthetically into the label under test.
            test.zustellen(nachricht);
            test.kopieren("INBOX", ordner, id);
        }
        return id;
    }

    private ImapPostfach postfach() {
        return new ImapPostfach(Duration.ofSeconds(10), "Archiviert", "Quarantaene",
                Optional.of(Path.of(System.getProperty("user.dir"), "target", "greenmail", "ca.pem")), verzeichnis,
                60_000_000L, Duration.ofHours(1), 100 * 1024, Clock.systemUTC());
    }

    private static final class MutableUhr extends Clock {
        Instant jetzt;

        MutableUhr(Instant jetzt) {
            this.jetzt = jetzt;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }
    }
}
