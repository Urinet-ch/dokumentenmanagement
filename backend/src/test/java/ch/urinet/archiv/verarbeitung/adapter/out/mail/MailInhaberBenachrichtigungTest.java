package ch.urinet.archiv.verarbeitung.adapter.out.mail;

import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Abweichung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Art;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Zaehler;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Meldung zum abgebrochenen Prüflauf (Req 15.5): Lauf-ID, Teil, Phase, Marke, Zähler, die ersten Abweichungen und
 * Fehler-ID, sonst nichts (Req 16.7).
 */
class MailInhaberBenachrichtigungTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID LAUF = UUID.fromString("5d0c8f3e-1a2b-4c3d-9e8f-7a6b5c4d3e2f");
    private static final UUID FEHLER = UUID.fromString("9f8e7d6c-5b4a-4321-8fed-cba987654321");
    private static final UUID DOKUMENT = UUID.fromString("0b6f0a52-6d1e-4f43-9a8e-3a2d5c0e7f11");
    private static final String SCHLUESSEL = "archiv/2026/" + DOKUMENT + "/1-original.pdf";
    private static final Fortsetzung STAND = new Fortsetzung(Phase.ARCHIV_VERSIONEN, Optional.empty(),
            Optional.of(new Versionsseite.Marke(SCHLUESSEL, "v7")));
    private static final Zaehler BISHER = new Zaehler(3, 0, 0, 1, 0, 2);
    private static final List<Abweichung> ERSTE = List.of(
            new Abweichung(Art.PRUEFSUMME, Optional.of(DOKUMENT), SCHLUESSEL, Optional.of("v1")));

    private final List<Mail> gesendet = new ArrayList<>();

    @Test
    void abbruchNenntNurIdsZaehlerUndAbweichungen() {
        assertThat(MailInhaberBenachrichtigung.abbruchBetreff(LAUF, BISHER))
                .isEqualTo("UriNet Archiv: Prüflauf " + LAUF + " abgebrochen, 2 Abweichungen bis dahin");
        assertThat(MailInhaberBenachrichtigung.abbruchText(LAUF, 4, STAND, BISHER, ERSTE, FEHLER)).isEqualTo("""
                Der monatliche Prüflauf %1$s ist nach mehreren Versuchen abgebrochen und wird in diesem Monat nicht \
                fortgesetzt; der nächste Monatslauf beginnt von vorn.

                Teil: 4
                Phase: ARCHIV_VERSIONEN
                Nach Dokument: -
                Marke: %2$s, Version v7

                Bis zum Abbruch festgehalten:
                Geprüfte Dateien: 3
                Geprüfte Versionen unter archiv/: 0
                Geprüfte Manifeste: 0
                Verlängerte Sperren: 1
                Abweichungen: 2

                - PRUEFSUMME Dokument %3$s, %2$s, Version v1
                - … weitere in der Tabelle prueflauf_abweichung (lauf_id %1$s)

                Die betroffenen Dateien nicht ändern oder löschen und die Abweichungen klären.

                Prüfsummen und Sperren der Dateien unter archiv/ sind geprüft. Bis zum nächsten vollständigen Lauf \
                sind ab dieser Stelle die Versionen und Löschmarken unter archiv/ nicht geprüft, ebenso alle \
                Manifeste mit ihren Sperren und Löschmarken.
                Den Object Storage prüfen und im Log nach der Fehler-ID %4$s und der Lauf-ID suchen.
                """.formatted(LAUF, SCHLUESSEL, DOKUMENT, FEHLER));
    }

    @Test
    void abbruchOhneAbweichungenNenntKeineUndBittetNichtUmKlaerung() {
        Fortsetzung stand = new Fortsetzung(Phase.DATEIEN, Optional.of(DOKUMENT), Optional.empty());
        String text = MailInhaberBenachrichtigung.abbruchText(LAUF, 1, stand, Zaehler.NULL, List.of(), FEHLER);

        assertThat(MailInhaberBenachrichtigung.abbruchBetreff(LAUF, Zaehler.NULL))
                .isEqualTo("UriNet Archiv: Prüflauf " + LAUF + " abgebrochen");
        assertThat(text).contains("Nach Dokument: " + DOKUMENT,
                "Abweichungen: 0\n\n" + MailInhaberBenachrichtigung.nichtGeprueft(Phase.DATEIEN) + "\n")
                .doesNotContain("\n- ", "klären");
    }

    @Test
    void nichtGeprueftJePhase() {
        assertThat(MailInhaberBenachrichtigung.nichtGeprueft(Phase.DATEIEN))
                .startsWith("Bis zum nächsten vollständigen Lauf sind ab dieser Stelle Prüfsummen und Sperren");
        assertThat(MailInhaberBenachrichtigung.nichtGeprueft(Phase.ARCHIV_VERSIONEN))
                .startsWith("Prüfsummen und Sperren der Dateien unter archiv/ sind geprüft.");
        assertThat(MailInhaberBenachrichtigung.nichtGeprueft(Phase.MANIFEST_VERSIONEN))
                .contains("die Versionen unter archiv/ sind geprüft",
                        "Manifeste mit ihren Sperren und Löschmarken nicht");
    }

    @Test
    void abbruchGehtAnDenKonfiguriertenEmpfaenger() {
        benachrichtigung("inhaber@example.ch", mails -> gesendet.addAll(List.of(mails)))
                .prueflaufAbgebrochen(MANDANT, LAUF, 4, STAND, BISHER, ERSTE, FEHLER);

        assertThat(gesendet).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("inhaber@example.ch");
            assertThat(mail.getSubject()).isEqualTo(MailInhaberBenachrichtigung.abbruchBetreff(LAUF, BISHER));
            assertThat(mail.getText())
                    .isEqualTo(MailInhaberBenachrichtigung.abbruchText(LAUF, 4, STAND, BISHER, ERSTE, FEHLER));
        });
    }

    @Test
    void ohneEmpfaengerKeineMailUndEinFehlerBeimVersandBrichtNichtAb() {
        benachrichtigung(null, mails -> gesendet.addAll(List.of(mails)))
                .prueflaufAbgebrochen(MANDANT, LAUF, 4, STAND, BISHER, ERSTE, FEHLER);
        assertThat(gesendet).isEmpty();

        MailInhaberBenachrichtigung scheitert = benachrichtigung("inhaber@example.ch", mails -> {
            throw new IllegalStateException("SMTP nicht erreichbar");
        });
        assertThatCode(() -> scheitert.prueflaufAbgebrochen(MANDANT, LAUF, 4, STAND, BISHER, ERSTE, FEHLER))
                .doesNotThrowAnyException();
    }

    private static MailInhaberBenachrichtigung benachrichtigung(String empfaenger, Mailer mailer) {
        SmallRyeConfigBuilder config = new SmallRyeConfigBuilder();
        if (empfaenger != null) {
            config.withDefaultValue(MailInhaberBenachrichtigung.EMPFAENGER + MANDANT.wert(), empfaenger);
        }
        return new MailInhaberBenachrichtigung(mailer, config.build());
    }
}
