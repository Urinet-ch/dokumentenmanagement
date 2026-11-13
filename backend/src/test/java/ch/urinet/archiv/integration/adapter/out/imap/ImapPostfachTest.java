package ch.urinet.archiv.integration.adapter.out.imap;

import ch.urinet.archiv.integration.TestPostfach;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Abruf;
import ch.urinet.archiv.integration.application.mail.PostfachPort.Ziel;
import ch.urinet.archiv.integration.domain.mail.Postfach;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImapPostfach} gegen GreenMail ohne Quarkus (Spec 02, Req 1.1, 1.5): Eine Nachricht, die immer scheitert,
 * blockiert das Fenster {@code max} nicht, sie wird zurückgestellt und nach der Wartezeit wieder versucht.
 */
class ImapPostfachTest {

    @TempDir
    Path verzeichnis;

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
        MimeMessage nachricht = new MimeMessage(Session.getInstance(new Properties()));
        nachricht.setFrom("inhaber@example.invalid");
        nachricht.setSubject(betreff);
        nachricht.setText("Synthetischer Text");
        nachricht.saveChanges();
        String id = "<" + UUID.randomUUID() + "@test.example.invalid>";
        nachricht.setHeader("Message-ID", id);
        test.zustellen(nachricht);
        return id;
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
