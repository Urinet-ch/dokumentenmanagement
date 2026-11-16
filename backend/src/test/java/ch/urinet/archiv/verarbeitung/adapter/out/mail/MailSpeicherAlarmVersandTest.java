package ch.urinet.archiv.verarbeitung.adapter.out.mail;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.verarbeitung.application.SpeicherMessung;
import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung.Stufe;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Versand der Speicher-Mail (Req 16.5, 16.7): ohne Empfänger keine Mail und «nicht zugestellt» (die Überwachung
 * versucht es wieder), bei einem Fehler im Versand dasselbe, ohne dass Empfänger oder Meldung im Log stehen.
 */
class MailSpeicherAlarmVersandTest {

    private static final long MIB = 1024 * 1024;
    private static final SpeicherMessung MESSUNG = new SpeicherMessung("Metaspace", 90 * MIB, 100 * MIB);

    private static MailSpeicherAlarm alarm(String empfaenger, Mailer mailer, String rolle) {
        SmallRyeConfigBuilder config = new SmallRyeConfigBuilder();
        if (empfaenger != null) {
            config.withDefaultValue(MailInhaberBenachrichtigung.EMPFAENGER + "urinet", empfaenger);
        }
        return new MailSpeicherAlarm(mailer, config.build(), "urinet", rolle);
    }

    @Test
    @Tag("req-16.5")
    void ohneOderMitLeeremEmpfaengerGehtKeineMailUndDerVersandGiltAlsNichtZugestellt() {
        for (String empfaenger : new String[]{null, "", "  \t "}) {
            List<Mail> gesendet = new ArrayList<>();
            MailSpeicherAlarm alarm = alarm(empfaenger, mails -> gesendet.addAll(List.of(mails)), "api,worker");

            assertThat(alarm.speicherKnapp(Stufe.WARNUNG, MESSUNG)).isFalse();
            assertThat(gesendet).isEmpty();
        }
    }

    @Test
    @Tag("req-16.5")
    void mitEmpfaengerGehtEineMailNurAnDenBetriebsEmpfaenger() {
        List<Mail> gesendet = new ArrayList<>();
        MailSpeicherAlarm alarm = alarm(" inhaber@example.invalid ", mails -> gesendet.addAll(List.of(mails)), "worker");

        assertThat(alarm.speicherKnapp(Stufe.KRITISCH, MESSUNG)).isTrue();

        assertThat(gesendet).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("inhaber@example.invalid");
            assertThat(mail.getSubject()).isEqualTo("UriNet Archiv: Speicher der Worker kritisch (Metaspace 90 %)");
            assertThat(mail.getText()).startsWith("Speicher der Worker kritisch: Metaspace bei 90 % (90 von 100 MiB).");
        });
    }

    @Test
    @Tag("req-16.5")
    void fehlerImVersandMeldetNichtZugestelltUndLoggtWederEmpfaengerNochMeldung() {
        MailSpeicherAlarm alarm = alarm("inhaber@example.invalid", mails -> {
            throw new IllegalStateException("synthetisches-betriebsdetail smtp.example.invalid");
        }, "api");

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThat(alarm.speicherKnapp(Stufe.WARNUNG, MESSUNG)).isFalse();

            assertThat(log.volltext()).contains("Metaspace").contains(IllegalStateException.class.getName())
                    .doesNotContain("inhaber@example.invalid", "synthetisches-betriebsdetail",
                            "smtp.example.invalid");
        }
    }

    @Test
    @Tag("req-16.5")
    void textUndBetreffEnthaltenNurBereichProzentUndMengen() {
        String text = MailSpeicherAlarm.text("API", Stufe.WARNUNG, new SpeicherMessung("Heap", 1, 3))
                + MailSpeicherAlarm.betreff("API", Stufe.WARNUNG, new SpeicherMessung("Heap", 1, 3));

        assertThat(text).contains("Heap bei 33 % (0 von 0 MiB)").doesNotContainPattern("\\d+\\.\\d+\\.\\d+\\.\\d+")
                .doesNotContain("localhost", "@", "://");
    }
}
