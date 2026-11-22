package ch.urinet.archiv.benutzer.adapter.out.mail;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import io.quarkus.mailer.Mail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailBenutzerMitteilungTest {
    @Test
    void sendetVorgesehenenTextAnDieBisherigeAdresse() {
        List<Mail> gesendet = new ArrayList<>();
        var mitteilung = new MailBenutzerMitteilung(mails -> gesendet.addAll(List.of(mails)));

        mitteilung.emailGeaendert("alt@example.invalid");

        assertThat(gesendet).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("alt@example.invalid");
            assertThat(mail.getSubject()).isEqualTo("UriNet Archiv: E-Mail-Adresse geändert");
            assertThat(mail.getText()).isEqualTo("""
                    Die E-Mail-Adresse für Ihren Zugang zum UriNet Archiv wurde geändert. Falls Sie diese Änderung nicht erwartet haben, wenden Sie sich umgehend an die Verwaltung.
                    """);
        });
    }

    @Test
    void versandfehlerWirdOhneAdresseOderFehlerdetailsGemeldet() {
        var mitteilung = new MailBenutzerMitteilung(mails -> {
            throw new IllegalStateException("alt@example.invalid");
        });
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThatThrownBy(() -> mitteilung.emailGeaendert("alt@example.invalid"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Mitteilung zur E-Mail-Änderung nicht versandt")
                    .hasNoCause();
            assertThat(log.volltext()).doesNotContain("alt@example.invalid");
        }
    }
}
