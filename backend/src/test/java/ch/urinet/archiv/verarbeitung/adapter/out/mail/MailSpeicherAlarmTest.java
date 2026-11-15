package ch.urinet.archiv.verarbeitung.adapter.out.mail;

import ch.urinet.archiv.verarbeitung.application.SpeicherMessung;
import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung.Stufe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Mail nennt nur Bereich, Prozent und Mengen, keine Betriebsangaben (Req 16.5, 16.7). */
class MailSpeicherAlarmTest {

    private static final long MIB = 1024 * 1024;
    private static final SpeicherMessung MESSUNG = new SpeicherMessung("Metaspace", 167 * MIB, 192 * MIB);

    @Test
    @Tag("req-16.5")
    void textNenntBereichProzentUndMengen() {
        assertThat(MailSpeicherAlarm.betreff("API", Stufe.WARNUNG, MESSUNG))
                .isEqualTo("UriNet Archiv: Speicher der API knapp (Metaspace 86 %)");
        assertThat(MailSpeicherAlarm.text("API", Stufe.WARNUNG, MESSUNG))
                .startsWith("Speicher der API knapp: Metaspace bei 86 % (167 von 192 MiB).");
        assertThat(MailSpeicherAlarm.betreff("API", Stufe.KRITISCH, MESSUNG)).contains("kritisch");
    }

    @Test
    @Tag("req-16.5")
    void bezeichnungNachRolle() {
        assertThat(MailSpeicherAlarm.bezeichnung("api")).isEqualTo("API");
        assertThat(MailSpeicherAlarm.bezeichnung("worker")).isEqualTo("Worker");
        assertThat(MailSpeicherAlarm.bezeichnung("api, worker")).isEqualTo("API und Worker");
    }
}
