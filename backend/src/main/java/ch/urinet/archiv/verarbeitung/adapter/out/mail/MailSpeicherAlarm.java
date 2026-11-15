package ch.urinet.archiv.verarbeitung.adapter.out.mail;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.SpeicherAlarm;
import ch.urinet.archiv.verarbeitung.application.SpeicherMessung;
import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung.Stufe;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Optional;

/**
 * Mail zum knappen Speicher der JVM an den Empfänger des Betriebs-Mandanten
 * ({@code archiv.benachrichtigung.empfaenger.<archiv.ueberwachung.betrieb-mandant>}, wie die Alarme zur Sicherung).
 * Ohne Empfänger oder bei einem Fehler im Versand bleibt es bei der Log-Zeile der Überwachung.
 */
@ApplicationScoped
public class MailSpeicherAlarm implements SpeicherAlarm {

    private static final Logger LOG = Logger.getLogger(MailSpeicherAlarm.class);
    private static final double MIB = 1024.0 * 1024.0;

    private final Mailer mailer;
    private final Config config;
    private final MandantCode betrieb;
    private final String bezeichnung;

    public MailSpeicherAlarm(Mailer mailer, Config config,
                             @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant") String betrieb,
                             @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.mailer = mailer;
        this.config = config;
        this.betrieb = new MandantCode(betrieb);
        this.bezeichnung = bezeichnung(rolle);
    }

    @Override
    public boolean speicherKnapp(Stufe stufe, SpeicherMessung messung) {
        Optional<String> empfaenger = config
                .getOptionalValue(MailInhaberBenachrichtigung.EMPFAENGER + betrieb.wert(), String.class)
                .map(String::strip).filter(s -> !s.isEmpty());
        if (empfaenger.isEmpty()) {
            return false;
        }
        try {
            mailer.send(Mail.withText(empfaenger.get(), betreff(bezeichnung, stufe, messung),
                    text(bezeichnung, stufe, messung)));
            LOG.infof("Inhaber über knappen Speicher (%s, %s) benachrichtigt", messung.pool(), stufe);
            return true;
        } catch (RuntimeException e) {
            LOG.warnf("Benachrichtigung zu knappem Speicher (%s) nicht versandt (%s)", messung.pool(),
                    e.getClass().getName());
            return false;
        }
    }

    static String bezeichnung(String rolle) {
        boolean api = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("api"::equals);
        boolean worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
        return api && worker ? "API und Worker" : worker ? "Worker" : "API";
    }

    static String betreff(String bezeichnung, Stufe stufe, SpeicherMessung messung) {
        return "UriNet Archiv: Speicher der " + bezeichnung + (stufe == Stufe.KRITISCH ? " kritisch" : " knapp")
                + " (" + messung.pool() + " " + (int) messung.prozent() + " %)";
    }

    static String text(String bezeichnung, Stufe stufe, SpeicherMessung messung) {
        return "Speicher der " + bezeichnung + (stufe == Stufe.KRITISCH ? " kritisch" : " knapp") + ": "
                + messung.pool() + " bei " + (int) messung.prozent() + " % (" + Math.round(messung.benutzt() / MIB)
                + " von " + Math.round(messung.maximum() / MIB) + " MiB).\n\n"
                + "Läuft ein Bereich voll, beendet sich die Anwendung mit OutOfMemoryError und startet neu. "
                + "Das Limit der betroffenen Einstellung der JVM prüfen und bei Bedarf erhöhen; "
                + "diese Meldung kommt je Bereich und Stufe einmal, bis der Wert wieder unter die Schwelle fällt.\n";
    }
}
