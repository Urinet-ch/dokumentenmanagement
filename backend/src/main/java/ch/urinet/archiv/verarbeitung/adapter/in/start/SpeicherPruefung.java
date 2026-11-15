package ch.urinet.archiv.verarbeitung.adapter.in.start;

import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Startprüfung der Speicherschwellen (Req 16.5): {@code warn-prozent} und {@code kritisch-prozent} müssen
 * {@code 0 < warn < kritisch <= 100} erfüllen, sonst bricht der Start ab, statt dass die Bean erst im ersten Takt
 * scheitert und die Warnung still ausfällt. Gilt in jeder Rolle.
 */
@ApplicationScoped
public class SpeicherPruefung {

    void beimStart(@Observes StartupEvent ereignis,
                   @ConfigProperty(name = "archiv.ueberwachung.speicher.warn-prozent") int warn,
                   @ConfigProperty(name = "archiv.ueberwachung.speicher.kritisch-prozent") int kritisch) {
        SpeicherUeberwachung.pruefeEinstellungen(warn, kritisch);
    }
}
