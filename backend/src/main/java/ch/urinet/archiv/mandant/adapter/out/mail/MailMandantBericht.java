package ch.urinet.archiv.mandant.adapter.out.mail;

import ch.urinet.archiv.mandant.application.MandantBerichtMail;
import ch.urinet.archiv.mandant.domain.MandantBericht;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.Optional;

/** Sendet nur die Kennzahlen des Monatsberichts an den konfigurierten Betreiber-Empfänger. */
@ApplicationScoped
public class MailMandantBericht implements MandantBerichtMail {

    private static final String EMPFAENGER = "archiv.benachrichtigung.empfaenger.";
    private static final Logger LOG = Logger.getLogger(MailMandantBericht.class);

    private final Mailer mailer;
    private final Config config;

    public MailMandantBericht(Mailer mailer, Config config) {
        this.mailer = mailer;
        this.config = config;
    }

    @Override
    public boolean senden(MandantBericht bericht) {
        Optional<String> empfaenger = config.getOptionalValue(EMPFAENGER + bericht.mandant().wert(), String.class)
                .map(String::strip).filter(s -> !s.isEmpty());
        if (empfaenger.isEmpty()) {
            LOG.warnf("Monatsbericht %s für Mandant %s nicht versandt: kein Empfänger konfiguriert",
                    bericht.monat(), bericht.mandant().wert());
            return false;
        }
        try {
            mailer.send(Mail.withText(empfaenger.get(), betreff(bericht), text(bericht)));
            return true;
        } catch (RuntimeException e) {
            LOG.warnf("Monatsbericht %s für Mandant %s nicht versandt (%s)", bericht.monat(),
                    bericht.mandant().wert(), e.getClass().getName());
            return false;
        }
    }

    private static String betreff(MandantBericht bericht) {
        return "UriNet Archiv: Monatsbericht " + bericht.mandant().wert() + " " + bericht.monat();
    }

    private static String text(MandantBericht bericht) {
        StringBuilder text = new StringBuilder("Monatsbericht für Mandant ")
                .append(bericht.mandant().wert()).append(" – ").append(bericht.monat()).append("\n\n")
                .append("Speicherbedarf (Byte): ").append(bericht.speicherBytes()).append("\n")
                .append("Dokumente nach Status:\n");
        bericht.dokumente().forEach((status, anzahl) ->
                text.append("- ").append(status).append(": ").append(anzahl).append('\n'));
        text.append("\nKI-Tokens nach Zweck (Eingabe/Ausgabe):\n");
        bericht.kiTokens().forEach((zweck, tokens) -> text.append("- ").append(zweck).append(": ")
                .append(tokens.eingabe()).append('/').append(tokens.ausgabe()).append('\n'));
        return text.append("\nChat-Fragen: ").append(bericht.chatFragen()).append('\n').toString();
    }
}
