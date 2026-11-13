package ch.urinet.archiv.verarbeitung.adapter.out.mail;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.InhaberBenachrichtigung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Benachrichtigung per E-Mail an den Empfänger aus {@code archiv.benachrichtigung.empfaenger.<mandant>}. Ohne Empfänger
 * oder bei einem Fehler beim Versand bleibt nur ein Log-Eintrag mit der Dokument-ID (Req 16.7).
 */
@ApplicationScoped
public class MailInhaberBenachrichtigung implements InhaberBenachrichtigung {

    static final String EMPFAENGER = "archiv.benachrichtigung.empfaenger.";

    private static final Logger LOG = Logger.getLogger(MailInhaberBenachrichtigung.class);

    private final Mailer mailer;
    private final Config config;

    public MailInhaberBenachrichtigung(Mailer mailer, Config config) {
        this.mailer = mailer;
        this.config = config;
    }

    @Override
    public void archivierungGescheitert(MandantCode mandant, UUID dokumentId, UUID fehlerId) {
        Optional<String> empfaenger = empfaenger(mandant);
        if (empfaenger.isEmpty()) {
            LOG.warnf("Archivierung von Dokument %s gescheitert (fehler-id=%s); kein Empfänger für die "
                    + "Benachrichtigung konfiguriert", dokumentId, fehlerId);
            return;
        }
        try {
            mailer.send(Mail.withText(empfaenger.get(), betreff(dokumentId), text(dokumentId, fehlerId)));
            LOG.infof("Inhaber über gescheiterte Archivierung von Dokument %s benachrichtigt", dokumentId);
        } catch (RuntimeException e) {
            LOG.warnf("Benachrichtigung zu Dokument %s nicht versandt (%s)", dokumentId, e.getClass().getName());
        }
    }

    @Override
    public void prueflaufAbweichungen(MandantCode mandant, UUID laufId, PrueflaufRegister.Zaehler zaehler,
                                      List<PrueflaufRegister.Abweichung> erste) {
        Optional<String> empfaenger = empfaenger(mandant);
        if (empfaenger.isEmpty()) {
            LOG.warnf("Prüflauf %s mit %d Abweichungen; kein Empfänger für die Benachrichtigung konfiguriert", laufId,
                    zaehler.abweichungen());
            return;
        }
        try {
            mailer.send(Mail.withText(empfaenger.get(), prueflaufBetreff(laufId, zaehler),
                    prueflaufText(laufId, zaehler, erste)));
            LOG.infof("Inhaber über Abweichungen im Prüflauf %s benachrichtigt", laufId);
        } catch (RuntimeException e) {
            LOG.warnf("Benachrichtigung zum Prüflauf %s nicht versandt (%s)", laufId, e.getClass().getName());
        }
    }

    @Override
    public void prueflaufAbgebrochen(MandantCode mandant, UUID laufId, int teil, PrueflaufRegister.Fortsetzung stand,
                                     UUID fehlerId) {
        Optional<String> empfaenger = empfaenger(mandant);
        if (empfaenger.isEmpty()) {
            LOG.warnf("Prüflauf %s Teil %d endgültig gescheitert (fehler-id=%s); kein Empfänger für die "
                    + "Benachrichtigung konfiguriert", laufId, teil, fehlerId);
            return;
        }
        try {
            mailer.send(Mail.withText(empfaenger.get(), abbruchBetreff(laufId),
                    abbruchText(laufId, teil, stand, fehlerId)));
            LOG.infof("Inhaber über den abgebrochenen Prüflauf %s benachrichtigt", laufId);
        } catch (RuntimeException e) {
            LOG.warnf("Benachrichtigung zum Prüflauf %s nicht versandt (%s)", laufId, e.getClass().getName());
        }
    }

    private Optional<String> empfaenger(MandantCode mandant) {
        return config.getOptionalValue(EMPFAENGER + mandant.wert(), String.class)
                .map(String::strip).filter(s -> !s.isEmpty());
    }

    static String prueflaufBetreff(UUID laufId, PrueflaufRegister.Zaehler zaehler) {
        return "UriNet Archiv: Prüflauf " + laufId + " mit " + zaehler.abweichungen() + " Abweichungen";
    }

    static String prueflaufText(UUID laufId, PrueflaufRegister.Zaehler zaehler,
                                List<PrueflaufRegister.Abweichung> erste) {
        StringBuilder text = new StringBuilder("Der monatliche Prüflauf ").append(laufId)
                .append(" hat Abweichungen festgestellt.\n\n")
                .append("Geprüfte Dateien: ").append(zaehler.dateien()).append('\n')
                .append("Geprüfte Versionen unter archiv/: ").append(zaehler.versionen()).append('\n')
                .append("Geprüfte Manifeste: ").append(zaehler.manifeste()).append('\n')
                .append("Verlängerte Sperren: ").append(zaehler.verlaengert()).append('\n')
                .append("Abweichungen: ").append(zaehler.abweichungen()).append("\n\n");
        for (PrueflaufRegister.Abweichung a : erste) {
            text.append("- ").append(a.art()).append(" Dokument ")
                    .append(a.dokumentId().map(UUID::toString).orElse("-")).append(", ").append(a.schluessel())
                    .append(", Version ").append(a.versionId().orElse("-")).append('\n');
        }
        if (erste.size() < zaehler.abweichungen()) {
            text.append("- … weitere in der Tabelle prueflauf_abweichung (lauf_id ").append(laufId).append(")\n");
        }
        return text.append("\nDie betroffenen Dateien nicht ändern oder löschen und die Abweichungen klären; "
                + "Details im Log unter der Lauf-ID.\n").toString();
    }

    static String abbruchBetreff(UUID laufId) {
        return "UriNet Archiv: Prüflauf " + laufId + " abgebrochen";
    }

    static String abbruchText(UUID laufId, int teil, PrueflaufRegister.Fortsetzung stand, UUID fehlerId) {
        return "Der monatliche Prüflauf " + laufId + " ist nach mehreren Versuchen abgebrochen und wird in diesem "
                + "Monat nicht fortgesetzt; der nächste Monatslauf beginnt von vorn.\n\n"
                + "Teil: " + teil + '\n'
                + "Phase: " + stand.phase() + '\n'
                + "Nach Dokument: " + stand.nachDokument().map(UUID::toString).orElse("-") + '\n'
                + "Marke: " + stand.marke().map(m -> m.schluessel() + ", Version " + m.versionId().orElse("-"))
                .orElse("-") + "\n\n"
                + "Den Object Storage prüfen und im Log nach der Fehler-ID " + fehlerId + " und der Lauf-ID suchen. "
                + "Bis zum nächsten vollständigen Lauf sind Prüfsummen und Sperren ab dieser Stelle nicht geprüft.\n";
    }

    static String betreff(UUID dokumentId) {
        return "UriNet Archiv: Archivierung von Dokument " + dokumentId + " gescheitert";
    }

    static String text(UUID dokumentId, UUID fehlerId) {
        return "Die Archivierung des Dokuments " + dokumentId + " ist gescheitert. Das Dokument steht im Eingangskorb "
                + "mit Status ARCHIVIERUNG_FEHLER; die bestätigten Metadaten sind erhalten.\n\n"
                + "Im Eingangskorb «Wiederholen» wählen. Bleibt der Fehler, im Log nach der Fehler-ID " + fehlerId
                + " suchen.\n";
    }
}
