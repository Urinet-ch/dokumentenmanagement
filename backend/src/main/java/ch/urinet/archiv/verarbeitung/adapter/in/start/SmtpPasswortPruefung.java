package ch.urinet.archiv.verarbeitung.adapter.in.start;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.function.Function;

/**
 * Startprüfung des SMTP-Passworts (Aufgabe 5.6, tech.md Regel 7, design.md «Secrets und Konfiguration»): Mit
 * {@code archiv.secrets.smtp-passwort-nur-datei} (prod) kommt das Passwort des Postfachs nur aus der Secret-Datei
 * {@code archiv_smtp_passwort}. Die Datei ist optional; ohne Wert startet das Archiv, und Benachrichtigungen bleiben im
 * Log. Übersteuert aber eine Umgebungsvariable oder Systemproperty die Datei ({@code ARCHIV_SMTP_PASSWORT}) oder den
 * Zugang selbst ({@code QUARKUS_MAILER_PASSWORD} statt {@code ${archiv_smtp_passwort:}}), bricht der Start ab, wie beim
 * Datenschlüssel. Die Meldung nennt nur Namen, nie den Wert.
 */
@ApplicationScoped
public class SmtpPasswortPruefung {

    static final String DATEI = "archiv_smtp_passwort";
    static final String ZUGANG = "quarkus.mailer.password";
    static final String VERWEIS = "${" + DATEI + ":}";
    private static final String DATEI_QUELLE = "FileSystemConfigSource[";
    private static final Logger LOG = Logger.getLogger(SmtpPasswortPruefung.class);

    void beimStart(@Observes StartupEvent ereignis,
                   @ConfigProperty(name = "archiv.secrets.smtp-passwort-nur-datei", defaultValue = "false")
                   boolean nurDatei) {
        if (nurDatei && !pruefen(ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)::getConfigValue)) {
            LOG.infof("Mail-Versand: Secret-Datei %s leer oder nicht vorhanden; Benachrichtigungen bleiben im Log",
                    DATEI);
        }
    }

    /**
     * Wirft, wenn Datei oder Zugang übersteuert sind, auch mit leerem Wert (eine leere Umgebungsvariable überdeckte
     * sonst still eine gefüllte Datei; Nachprüfung Review PR #133); sonst {@code true}, wenn ein Passwort aus der
     * Datei da ist.
     */
    static boolean pruefen(Function<String, ConfigValue> lesen) {
        ConfigValue datei = lesen.apply(DATEI);
        boolean dateiWert = gesetzt(datei);
        if (vorhanden(datei) && (datei.getSourceName() == null || !datei.getSourceName().startsWith(DATEI_QUELLE))) {
            throw new IllegalStateException(DATEI + " muss aus der Secret-Datei kommen (tech.md Regel 7); "
                    + "Umgebungsvariable ARCHIV_SMTP_PASSWORT bzw. Systemproperty entfernen und neu starten.");
        }
        ConfigValue zugang = lesen.apply(ZUGANG);
        String roh = zugang == null ? null : zugang.getRawValue() != null ? zugang.getRawValue() : zugang.getValue();
        if (roh != null && !VERWEIS.equals(roh.trim())) {
            throw new IllegalStateException(ZUGANG + " muss aus der Secret-Datei " + DATEI
                    + " kommen (tech.md Regel 7); Umgebungsvariable QUARKUS_MAILER_PASSWORD bzw. Systemproperty "
                    + "entfernen und neu starten.");
        }
        return dateiWert;
    }

    /** Eine Quelle liefert den Schlüssel, auch leer. */
    private static boolean vorhanden(ConfigValue wert) {
        return wert != null && (wert.getRawValue() != null || wert.getValue() != null || wert.getSourceName() != null);
    }

    private static boolean gesetzt(ConfigValue wert) {
        return wert != null && wert.getValue() != null && !wert.getValue().isBlank();
    }
}
