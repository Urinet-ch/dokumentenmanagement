package ch.urinet.archiv.benutzer.adapter.out.mail;

import ch.urinet.archiv.benutzer.application.BenutzerMitteilung;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@ApplicationScoped
public class MailBenutzerMitteilung implements BenutzerMitteilung {
    private static final Logger LOG = Logger.getLogger(MailBenutzerMitteilung.class);
    private static final String VORLAGE = "/benutzer/email-geaendert.txt";
    private final Mailer mailer;
    private final String betreff;
    private final String text;

    public MailBenutzerMitteilung(Mailer mailer) {
        this.mailer = Objects.requireNonNull(mailer);
        try (InputStream strom = MailBenutzerMitteilung.class.getResourceAsStream(VORLAGE)) {
            if (strom == null) {
                throw new IllegalStateException("Vorlage für die E-Mail-Mitteilung fehlt");
            }
            String vorlage = new String(strom.readAllBytes(), StandardCharsets.UTF_8);
            int trennung = vorlage.indexOf("\n\n");
            if (trennung < 1 || vorlage.substring(trennung + 2).isBlank()) {
                throw new IllegalStateException("Vorlage für die E-Mail-Mitteilung ist ungültig");
            }
            betreff = vorlage.substring(0, trennung);
            text = vorlage.substring(trennung + 2);
        } catch (IOException e) {
            throw new IllegalStateException("Vorlage für die E-Mail-Mitteilung ist nicht lesbar", e);
        }
    }

    @Override
    public void emailGeaendert(String bisherigeAdresse) {
        try {
            mailer.send(Mail.withText(Objects.requireNonNull(bisherigeAdresse), betreff, text));
        } catch (RuntimeException e) {
            LOG.warnf("Mitteilung zur E-Mail-Änderung nicht versandt (%s).", e.getClass().getSimpleName());
            throw new IllegalStateException("Mitteilung zur E-Mail-Änderung nicht versandt");
        }
    }
}
