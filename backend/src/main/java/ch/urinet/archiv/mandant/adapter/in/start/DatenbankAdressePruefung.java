package ch.urinet.archiv.mandant.adapter.in.start;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Fail closed für die Adresse der Datenbank (Aufgabe 1.9): Quarkus schaltet eine Datasource ohne URL still ab, das Archiv
 * liefe dann ohne Datenbank. In {@code prod} ({@code archiv.db.url-pflicht=true}) bricht der Start darum ab, wenn
 * {@code quarkus.datasource.jdbc.url} fehlt. Die Meldung nennt nur den Schlüssel.
 */
@ApplicationScoped
public class DatenbankAdressePruefung {

    static final String PFLICHT = "archiv.db.url-pflicht";

    void beimStart(@Observes StartupEvent start,
                   @ConfigProperty(name = PFLICHT, defaultValue = "false") boolean pflicht,
                   @ConfigProperty(name = "quarkus.datasource.jdbc.url") Optional<String> url) {
        pruefen(pflicht, url);
    }

    static void pruefen(boolean pflicht, Optional<String> url) {
        if (pflicht && url.filter(wert -> !wert.isBlank()).isEmpty()) {
            throw new IllegalStateException("Die Adresse der Datenbank fehlt: quarkus.datasource.jdbc.url (Umgebungsvariable "
                    + "QUARKUS_DATASOURCE_JDBC_URL) setzen und neu starten.");
        }
    }
}
