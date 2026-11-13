package ch.urinet.archiv.export.adapter.out.mail;

import ch.urinet.archiv.export.application.ExportBenachrichtigung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Meldet einen fertigen Jahresexport an den Empfänger der Benachrichtigungen des Mandanten
 * ({@code archiv.benachrichtigung.empfaenger.<mandant>}, wie die übrigen Benachrichtigungen an den Inhaber). Die Mail
 * nennt nur den Link auf die Seite «Export» der PWA ({@code archiv.adresse}) und die Export-ID (Req 7.6, 16.7): nie
 * die vorsignierte Adresse, weil sie 24 Stunden lang ohne Anmeldung das ganze Jahr öffnet. Ohne Empfänger (in
 * Produktion bis zur Einrichtung) oder bei einem Fehler beim Versand bleibt ein Log-Eintrag mit der Export-ID.
 */
@ApplicationScoped
public class MailExportBenachrichtigung implements ExportBenachrichtigung {

    static final String EMPFAENGER = "archiv.benachrichtigung.empfaenger.";
    private static final Logger LOG = Logger.getLogger(MailExportBenachrichtigung.class);

    private final Mailer mailer;
    private final Config config;
    private final String adresse;

    public MailExportBenachrichtigung(Mailer mailer, Config config,
                                      @ConfigProperty(name = "archiv.adresse") String adresse) {
        this.mailer = mailer;
        this.config = config;
        this.adresse = adresse.endsWith("/") ? adresse.substring(0, adresse.length() - 1) : adresse;
    }

    @Override
    public void fertig(MandantCode mandant, UUID exportId) {
        Optional<String> empfaenger = config.getOptionalValue(EMPFAENGER + mandant.wert(), String.class)
                .map(String::strip).filter(s -> !s.isEmpty());
        if (empfaenger.isEmpty()) {
            LOG.warnf("Export %s fertig; kein Empfänger für die Benachrichtigung konfiguriert", exportId);
            return;
        }
        try {
            mailer.send(Mail.withText(empfaenger.get(), betreff(exportId), text(adresse, exportId)));
            LOG.infof("Export %s gemeldet", exportId);
        } catch (RuntimeException e) {
            LOG.warnf("Meldung zum Export %s nicht versandt (%s)", exportId, e.getClass().getName());
        }
    }

    static String betreff(UUID exportId) {
        return "UriNet Archiv: Jahresexport " + exportId + " bereit";
    }

    static String text(String adresse, UUID exportId) {
        return "Der Jahresexport " + exportId + " ist bereit.\n\n"
                + "Herunterladen nach der Anmeldung: " + adresse + "/export\n\n"
                + "Der Download-Link gilt 24 Stunden ab Fertigstellung.\n";
    }
}
