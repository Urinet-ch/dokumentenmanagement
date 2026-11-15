package ch.urinet.archiv.export.adapter.out.mail;

import ch.urinet.archiv.export.application.ExportBenachrichtigung;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.mailer.Mail;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MailExportBenachrichtigungTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID EXPORT_ID = UUID.fromString("288f8fb7-43dd-45d5-a040-9a9d8b8b8506");

    @Test
    void ohneOderMitLeeremEmpfaengerWirdNichtGesendetUndDieWarnungNenntDieExportId() {
        for (String empfaenger : new String[]{null, " \t "}) {
            List<Mail> gesendet = new ArrayList<>();
            MailExportBenachrichtigung benachrichtigung = benachrichtigung(empfaenger,
                    mails -> gesendet.addAll(List.of(mails)), "https://archiv.example.invalid");

            try (LogMitschnitt log = LogMitschnitt.starten()) {
                assertThatCode(() -> benachrichtigung.fertig(MANDANT, EXPORT_ID)).doesNotThrowAnyException();

                assertThat(gesendet).isEmpty();
                assertThat(log.eintraege())
                        .filteredOn(e -> MailExportBenachrichtigung.class.getName().equals(e.getLoggerName()))
                        .singleElement().satisfies(e -> assertThat(log.nachricht(e)).isEqualTo("Export " + EXPORT_ID
                                + " fertig; kein Empfänger für die Benachrichtigung konfiguriert"));
                assertThat(log.volltext()).doesNotContain("synthetischer-beleginhalt",
                        "https://download.example.invalid");
            }
        }
    }

    @Test
    void mailEnthaeltNurExportIdUndPwaLinkMitEinemSchraegstrich() {
        List<Mail> gesendet = new ArrayList<>();
        ExportBenachrichtigung benachrichtigung = benachrichtigung("inhaber@example.invalid",
                mails -> gesendet.addAll(List.of(mails)), "https://archiv.example.invalid/");

        benachrichtigung.fertig(MANDANT, EXPORT_ID);

        assertThat(gesendet).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("inhaber@example.invalid");
            assertThat(mail.getSubject()).contains(EXPORT_ID.toString())
                    .doesNotContain("dateiname.zip", "https://download.example.invalid");
            assertThat(mail.getText()).isEqualTo("""
                    Der Jahresexport %s ist bereit.

                    Herunterladen nach der Anmeldung: https://archiv.example.invalid/export

                    Der Download-Link gilt 24 Stunden ab Fertigstellung.
                    """.formatted(EXPORT_ID));
            assertThat(mail.getText()).contains(EXPORT_ID.toString(), "https://archiv.example.invalid/export")
                    .doesNotContain("//export", "dateiname.zip", "https://download.example.invalid");
        });
    }

    @Test
    void mailerFehlerBrichtNichtAbUndLoggtNurFehlerklasseUndExportId() {
        ExportBenachrichtigung benachrichtigung = benachrichtigung("inhaber@example.invalid",
                mails -> {
                    throw new IllegalStateException("synthetischer-beleginhalt");
                }, "https://archiv.example.invalid");

        try (LogMitschnitt log = LogMitschnitt.starten()) {
            assertThatCode(() -> benachrichtigung.fertig(MANDANT, EXPORT_ID)).doesNotThrowAnyException();

            assertThat(log.eintraege())
                    .filteredOn(e -> MailExportBenachrichtigung.class.getName().equals(e.getLoggerName()))
                    .singleElement().satisfies(e -> assertThat(log.nachricht(e)).isEqualTo("Meldung zum Export "
                            + EXPORT_ID + " nicht versandt (" + IllegalStateException.class.getName() + ")"));
            assertThat(log.volltext()).doesNotContain("synthetischer-beleginhalt", "dateiname.zip",
                    "https://download.example.invalid");
        }
    }

    private static MailExportBenachrichtigung benachrichtigung(String empfaenger,
                                                                io.quarkus.mailer.Mailer mailer,
                                                                String adresse) {
        SmallRyeConfigBuilder config = new SmallRyeConfigBuilder();
        if (empfaenger != null) {
            config.withDefaultValue(MailExportBenachrichtigung.EMPFAENGER + MANDANT.wert(), empfaenger);
        }
        return new MailExportBenachrichtigung(mailer, config.build(), adresse);
    }
}
